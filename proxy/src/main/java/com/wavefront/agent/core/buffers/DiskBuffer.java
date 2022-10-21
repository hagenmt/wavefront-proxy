package com.wavefront.agent.core.buffers;

import com.wavefront.common.logger.MessageDedupingLogger;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.util.JmxGauge;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import org.apache.activemq.artemis.api.core.ActiveMQAddressFullException;
import org.apache.activemq.artemis.api.core.management.AddressControl;

public class DiskBuffer extends ActiveMQBuffer implements Buffer {
  private static final Logger log = Logger.getLogger(DiskBuffer.class.getCanonicalName());
  private static final Logger slowLog =
      new MessageDedupingLogger(Logger.getLogger(MemoryBuffer.class.getCanonicalName()), 1000, 1);

  public DiskBuffer(int level, String name, DiskBufferConfig cfg) {
    super(level, name, true, cfg.buffer, cfg.maxMemory);

    try {
      ObjectName addressObjectName =
          new ObjectName(String.format("org.apache.activemq.artemis:broker=\"%s\"", name));
      Metrics.newGauge(
          new MetricName("buffer." + name, "", "diskUsage"),
          new JmxGauge(addressObjectName, "DiskStoreUsage"));
      Metrics.newGauge(
          new MetricName("buffer." + name, "", "diskUsageMax"),
          new JmxGauge(addressObjectName, "MaxDiskUsage"));

    } catch (MalformedObjectNameException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void sendPoints(String queue, List<String> points) throws ActiveMQAddressFullException {
    if (isFull()) {
      slowLog.log(Level.SEVERE, "Memory Queue full");
      throw new ActiveMQAddressFullException();
    }
    super.sendPoints(queue, points);
  }

  @Override
  public String getName() {
    return "Disk";
  }

  @Override
  public int getPriority() {
    return Thread.NORM_PRIORITY;
  }

  public boolean isFull() {
    return activeMQServer.getPagingManager().isDiskFull();
  }

  public void truncate() {
    Object[] addresses = activeMQServer.getManagementService().getResources(AddressControl.class);

    try {
      for (Object obj : addresses) {
        AddressControl address = (AddressControl) obj;
        if (!address.getAddress().startsWith("active")) {
          address.purge();
          log.info(address.getAddress() + " buffer truncated");
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
