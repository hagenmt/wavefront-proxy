package com.wavefront.agent.core.buffers;

import static org.apache.activemq.artemis.core.settings.impl.AddressFullMessagePolicy.FAIL;
import static org.apache.activemq.artemis.core.settings.impl.AddressFullMessagePolicy.PAGE;

import com.wavefront.agent.core.queues.QueueInfo;
import com.wavefront.agent.core.queues.QueueStats;
import com.wavefront.agent.data.EntityRateLimiter;
import com.wavefront.common.logger.MessageDedupingLogger;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Gauge;
import com.yammer.metrics.core.Histogram;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.util.JmxGauge;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import io.netty.buffer.ByteBuf;
import org.apache.activemq.artemis.api.core.*;
import org.apache.activemq.artemis.api.core.client.*;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.JournalType;
import org.apache.activemq.artemis.core.server.impl.ActiveMQServerImpl;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.spi.core.security.ActiveMQJAASSecurityManager;

public abstract class ActiveMQBuffer implements Buffer {
  private static final Logger log = Logger.getLogger(ActiveMQBuffer.class.getCanonicalName());
  private static final Logger slowLog =
      new MessageDedupingLogger(Logger.getLogger(ActiveMQBuffer.class.getCanonicalName()), 1000, 1);

  final ActiveMQServer activeMQServer;

  private final Map<String, Session> producers = new ConcurrentHashMap<>();
  private final Map<String, Session> consumers = new ConcurrentHashMap<>();

  protected final Map<String, PointsGauge> countMetrics = new HashMap<>();
  private final Map<String, Gauge<Object>> sizeMetrics = new HashMap<>(); // TODO review
  private final Map<String, Histogram> timeMetrics = new HashMap<>();

  final String name;
  private final int serverID;
  private final ClientSessionFactory factory;
  private final ServerLocator serverLocator;
  protected Buffer nextBuffer;

  public ActiveMQBuffer(
      int serverID, String name, boolean persistenceEnabled, File buffer, long maxMemory) {
    this.serverID = serverID;
    this.name = name;

    Configuration config = new ConfigurationImpl();
    config.setName(name);
    config.setSecurityEnabled(false);
    config.setPersistenceEnabled(persistenceEnabled);
    config.setMessageExpiryScanPeriod(persistenceEnabled ? 0 : 1_000);
    config.setGlobalMaxSize(maxMemory);

    try {
      Path tmpBuffer = Files.createTempDirectory("wfproxy");
      config.setPagingDirectory(tmpBuffer.toString());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    if (persistenceEnabled) {
      config.setMaxDiskUsage(70);
      config.setJournalDirectory(new File(buffer, "journal").getAbsolutePath());
      config.setBindingsDirectory(new File(buffer, "bindings").getAbsolutePath());
      config.setLargeMessagesDirectory(new File(buffer, "largemessages").getAbsolutePath());
      config.setPagingDirectory(new File(buffer, "paging").getAbsolutePath());
      config.setCreateBindingsDir(true);
      config.setCreateJournalDir(true);
      config.setJournalLockAcquisitionTimeout(10);
      config.setJournalType(JournalType.NIO);
    }

    ActiveMQJAASSecurityManager securityManager = new ActiveMQJAASSecurityManager();
    activeMQServer = new ActiveMQServerImpl(config, securityManager);
    activeMQServer.registerActivationFailureListener(
        exception ->
            log.severe(
                "error creating buffer, "
                    + exception.getMessage()
                    + ". Review if there is another Proxy running."));

    try {
      config.addAcceptorConfiguration("in-vm", "vm://" + serverID);
      activeMQServer.start();
    } catch (Exception e) {
      log.log(Level.SEVERE, "error creating buffer", e);
      System.exit(-1);
    }

    if (!activeMQServer.isActive()) {
      System.exit(-1);
    }

    AddressSettings addressSetting =
        new AddressSettings()
            .setMaxSizeMessages(-1)
            .setMaxExpiryDelay(-1L)
            .setMaxDeliveryAttempts(-1)
            .setManagementBrowsePageSize(Integer.MAX_VALUE);

    if (persistenceEnabled) {
      addressSetting.setMaxSizeBytes(-1);
      addressSetting.setAddressFullMessagePolicy(PAGE);
    } else {
      addressSetting.setMaxSizeBytes(maxMemory);
      addressSetting.setAddressFullMessagePolicy(FAIL);
    }

    activeMQServer.getAddressSettingsRepository().setDefault(addressSetting);

    try {
      String url = getUrl();
      serverLocator = ActiveMQClient.createServerLocator(url);
      factory = serverLocator.createSessionFactory();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  protected String getUrl() {
    return "vm://" + serverID;
  }

  @Override
  public void registerNewQueueInfo(QueueInfo queue) {
    for (int i = 0; i < queue.getNumberThreads(); i++) {
      createQueue(queue.getName(), i);
    }

    try {
      registerQueueMetrics(queue);
    } catch (MalformedObjectNameException e) {
      log.log(Level.SEVERE, "error", e);
    }
  }

  void registerQueueMetrics(QueueInfo queue) throws MalformedObjectNameException {
    ObjectName addressObjectName =
        new ObjectName(
            String.format(
                "org.apache.activemq.artemis:broker=\"%s\",component=addresses,address=\"%s\"",
                name, queue.getName()));

    sizeMetrics.put(
        queue.getName(),
        Metrics.newGauge(
            new MetricName("buffer." + name + "." + queue.getName(), "", "size"),
            new JmxGauge(addressObjectName, "AddressSize")));

    Metrics.newGauge(
        new MetricName("buffer." + name + "." + queue.getName(), "", "usage"),
        new JmxGauge(addressObjectName, "AddressLimitPercent"));

    countMetrics.put(
        queue.getName(),
        (PointsGauge)
            Metrics.newGauge(
                new MetricName("buffer." + name + "." + queue.getName(), "", "points"),
                new PointsGauge(queue, activeMQServer)));

    timeMetrics.put(
        queue.getName(),
        Metrics.newHistogram(
            new MetricName("buffer." + name + "." + queue.getName(), "", "queue-time")));
  }

  public void shutdown() {
    try {
      for (Map.Entry<String, Session> entry : producers.entrySet()) {
        entry.getValue().close(); // session
        entry.getValue().close(); // producer
      }
      for (Map.Entry<String, Session> entry : consumers.entrySet()) {
        entry.getValue().close(); // session
        entry.getValue().close(); // consumer
      }

      activeMQServer.stop();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @Override
  public void sendPoints(String queue, List<String> points) throws ActiveMQAddressFullException {
    try {
      doSendPoints(queue, points);
    } catch (ActiveMQAddressFullException e) {
      slowLog.log(Level.SEVERE, "Memory Queue full");
      if (slowLog.isLoggable(Level.FINER)) {
        slowLog.log(Level.SEVERE, "", e);
      }
      if (nextBuffer != null) {
        nextBuffer.sendPoints(queue, points);
        QueueStats.get(queue).queuedFull.inc();
      } else {
        throw e;
      }
    }
  }

  public void doSendPoints(String queue, List<String> points) throws ActiveMQAddressFullException {
    String sessionKey = "sendMsg." + queue + "." + Thread.currentThread().getName();
    Session mqCtx =
        producers.computeIfAbsent(
            sessionKey,
            s -> {
              try {
                ClientSession session = factory.createSession();
                ClientProducer producer = session.createProducer(queue);
                return new Session(session, producer, serverLocator);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });

    try {
      ClientMessage message = mqCtx.session.createMessage(true);
      message.writeBodyBufferString(String.join("\n", points));
      message.putIntProperty("points", points.size());
      mqCtx.producer.send(message);
    } catch (ActiveMQAddressFullException e) {
      log.log(Level.FINE, "queue full: " + e.getMessage());
      throw e;
    } catch (ActiveMQObjectClosedException e) {
      log.log(Level.FINE, "connection close: " + e.getMessage());
      mqCtx.close();
      producers.remove(sessionKey);
      sendPoints(queue, points);
    } catch (Exception e) {
      log.log(Level.SEVERE, "error", e);
      throw new RuntimeException(e);
    }
  }

  @Override
  public void onMsgBatch(
      QueueInfo queue, int idx, int batchSize, EntityRateLimiter rateLimiter, OnMsgFunction func) {
    String sessionKey = "onMsgBatch." + queue.getName() + "." + Thread.currentThread().getName();
    Session mqCtx =
        consumers.computeIfAbsent(
            sessionKey,
            s -> {
              try {
                ClientSession session = factory.createSession(false, false);
                ClientConsumer consumer = session.createConsumer(queue.getName() + "." + idx);
                return new Session(session, consumer, serverLocator);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });

    try {
      long start = System.currentTimeMillis();
      mqCtx.session.start();
      List<String> batch = new ArrayList<>(batchSize);
      List<ClientMessage> toACK = new ArrayList<>();
      boolean done = false;
      boolean needRollBack = false;
      while ((batch.size() < batchSize) && !done && ((System.currentTimeMillis() - start) < 1000)) {
        ClientMessage msg = mqCtx.consumer.receive(100);
        if (msg != null) {
          List<String> points;
          ByteBuf buffer = msg.getBuffer();
          if(buffer!=null) {
            points = Arrays.asList(msg.getReadOnlyBodyBuffer().readString().split("\n"));
          } else {
            points = new ArrayList<>();
            log.warning("Empty message");
          }
          boolean ok = rateLimiter.tryAcquire(points.size());
          if (ok) {
            toACK.add(msg);
            batch.addAll(points);
          } else {
            slowLog.info("rate limit reached on queue '" + queue.getName() + "'");
            done = true;
            needRollBack = true;
          }
        } else {
          done = true;
        }
      }

      try {
        if (batch.size() > 0) {
          func.run(batch);
        }
        // commit all messages ACKed
        toACK.forEach(
            msg -> {
              try {
                msg.individualAcknowledge();
                timeMetrics.get(queue.getName()).update(start - msg.getTimestamp());
              } catch (ActiveMQException e) {
                throw new RuntimeException(e);
              }
            });
        mqCtx.session.commit();
        if (needRollBack) {
          // rollback all messages not ACKed (rate)
          mqCtx.session.rollback();
        }
      } catch (Exception e) {
        log.log(Level.SEVERE, e.toString());
        if (log.isLoggable(Level.FINER)) {
          log.log(Level.SEVERE, "error", e);
        }
        // ACK all messages and then rollback so fail count go up
        toACK.forEach(
            msg -> {
              try {
                msg.individualAcknowledge();
              } catch (ActiveMQException ex) {
                throw new RuntimeException(ex);
              }
            });
        mqCtx.session.rollback();
      }
    } catch (Throwable e) {
      log.log(Level.SEVERE, "error", e);
      mqCtx.close();
      consumers.remove(sessionKey);
    } finally {
      try {
        if (!mqCtx.session.isClosed()) {
          mqCtx.session.stop();
        }
      } catch (ActiveMQException e) {
        log.log(Level.SEVERE, "error", e);
        mqCtx.close();
        consumers.remove(sessionKey);
      }
    }
  }

  private void createQueue(String queueName, int i) {
    QueueConfiguration queue =
        new QueueConfiguration(queueName + (i < 0 ? "" : ("." + i)))
            .setAddress(queueName)
            .setRoutingType(RoutingType.ANYCAST);

    try (ClientSession session = factory.createSession()) {
      ClientSession.QueueQuery q = session.queueQuery(queue.getName());
      if (!q.isExists()) {
        session.createQueue(queue);
      }
    } catch (Exception e) {
      log.log(Level.SEVERE, "error", e);
    }
  }

  public void setNextBuffer(Buffer nextBuffer) {
    this.nextBuffer = nextBuffer;
  }

  private class Session {
    ClientSession session;
    ClientConsumer consumer;
    ServerLocator serverLocator;
    ClientProducer producer;

    Session(ClientSession session, ClientConsumer consumer, ServerLocator serverLocator) {
      this.session = session;
      this.consumer = consumer;
      this.serverLocator = serverLocator;
    }

    public Session(ClientSession session, ClientProducer producer, ServerLocator serverLocator) {
      this.session = session;
      this.producer = producer;
      this.serverLocator = serverLocator;
    }

    void close() {
      if (session != null) {
        try {
          session.close();
        } catch (Throwable e) {
        }
      }
      if (consumer != null) {
        try {
          consumer.close();
        } catch (Throwable e) {
        }
      }
      if (serverLocator != null) {
        try {
          serverLocator.close();
        } catch (Throwable e) {
        }
      }
      if (producer != null) {
        try {
          producer.close();
        } catch (Throwable e) {
        }
      }
    }
  }
}
