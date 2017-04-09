package com.cloudera.framework.testing.server.test;

import static org.junit.Assert.assertTrue;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.cloudera.framework.testing.TestConstants;
import com.cloudera.framework.testing.TestRunner;
import com.cloudera.framework.testing.server.MqttServer;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestRunner.class)
public class TestMqttServer implements TestConstants {

  private static final int MQTT_TIMEOUT_MS = 1000;
  private static final String TOPIC_NAME_TEST = "mytopic";

  @ClassRule
  public static MqttServer mqttServer = MqttServer.getInstance();

  @Test
  public void testMqtt() throws MqttException, InterruptedException {
    final CountDownLatch messageReceived = new CountDownLatch(1);
    MqttClient client = new MqttClient(mqttServer.getConnectString(), UUID.randomUUID().toString());
    client.connect();
    client.setCallback(new MqttCallback() {
      @Override
      public void connectionLost(Throwable cause) {
      }

      @Override
      public void messageArrived(String topic, MqttMessage message) throws Exception {
        messageReceived.countDown();
      }

      @Override
      public void deliveryComplete(IMqttDeliveryToken token) {
      }
    });
    client.subscribe(TOPIC_NAME_TEST);
    MqttMessage message = new MqttMessage();
    message.setPayload(UUID.randomUUID().toString().getBytes());
    client.publish(TOPIC_NAME_TEST, message);
    assertTrue(messageReceived.await(MQTT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    client.disconnect();
  }

  @Test
  public void testMqttAgain() throws MqttException, InterruptedException {
    testMqtt();
  }

}
