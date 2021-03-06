package org.owntracks.android.services;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.WorkerThread;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClientPersistence;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistable;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import org.greenrobot.eventbus.Subscribe;
import org.json.JSONException;
import org.json.JSONObject;
import org.owntracks.android.App;
import org.owntracks.android.messages.MessageBase;
import org.owntracks.android.messages.MessageCard;
import org.owntracks.android.messages.MessageClear;
import org.owntracks.android.messages.MessageCmd;
import org.owntracks.android.messages.MessageEvent;
import org.owntracks.android.messages.MessageLocation;
import org.owntracks.android.messages.MessageTransition;
import org.owntracks.android.messages.MessageWaypoint;
import org.owntracks.android.messages.MessageWaypoints;
import org.owntracks.android.services.MessageProcessor.EndpointState;
import org.owntracks.android.support.Events;
import org.owntracks.android.support.Parser;
import org.owntracks.android.support.interfaces.OutgoingMessageProcessor;
import org.owntracks.android.support.Preferences;
import org.owntracks.android.support.SocketFactory;
import org.owntracks.android.support.interfaces.StatefulServiceMessageProcessor;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.TimeUnit;

import timber.log.Timber;

public class MessageProcessorEndpointMqtt implements OutgoingMessageProcessor, StatefulServiceMessageProcessor {
	private static final String TAG = "ServiceMessageMqtt";

	private static final String MQTT_BUNDLE_KEY_MESSAGE_PAYLOAD = "MQTT_BUNDLE_KEY_MESSAGE_PAYLOAD";
	private static final String MQTT_BUNDLE_KEY_MESSAGE_TOPIC = "MQTT_BUNDLE_KEY_MESSAGE_TOPIC";
	private static final String MQTT_BUNDLE_KEY_MESSAGE_RETAINED = "MQTT_BUNDLE_KEY_MESSAGE_RETAINED";
	private static final String MQTT_BUNDLE_KEY_MESSAGE_QOS = "MQTT_BUNDLE_KEY_MESSAGE_QOS";

	private CustomMqttClient mqttClient;
	private MqttConnectOptions connectOptions;
	private String lastConnectionId;
	private static EndpointState state;

	synchronized boolean sendPing() {
		// Connects if not connected or sends a ping message if aleady connected
		if(checkConnection() && mqttClient!=null) {
			mqttClient.ping();
			return true;
		} else {
			return false;
		}
	}

	//synchronized int sendMessage(Bundle b) {
	synchronized int sendMessage(MessageBase m) {
		long messageId = m.getMessageId();//b.getLong(Scheduler.BUNDLE_KEY_MESSAGE_ID);

		// Try to connect on demand if disconnected
		// Do not try to do this if previous attempts were not successful
		// Normal reconnect scheduler will take over
		//if (!isConnected()) {
			//Timber.d("not connected. Pressure is %s", sendMessageConnectPressure);
			//if (sendMessageConnectPressure > 2) {
			//	Timber.d("connect pressure too high, falling back to normal scheduler");
			//	return App.getMessageProcessor().onMessageDeliveryFailed(messageId);
			//} else {
			//	Timber.d("pressure ok, connecting on demand");

				sendMessageConnectPressure++;
				if (!connect()) {
					Timber.v("failed connection attempts :%s", sendMessageConnectPressure);
					return App.getMessageProcessor().onMessageDeliveryFailed(messageId);
				}
			//}
		//}

		// Connection should be established

		try {
			IMqttDeliveryToken pubToken = this.mqttClient.publish(m.getTopic(), App.getParser().toJsonBytes(m), m.getQos(), m.getRetained());
			pubToken.waitForCompletion(TimeUnit.SECONDS.toMillis(30));

			Timber.v("message sent: %s", messageId);
			return App.getMessageProcessor().onMessageDelivered(messageId);
		} catch (MqttException e) {
			e.printStackTrace();
			return App.getMessageProcessor().onMessageDeliveryFailed(messageId);
		} catch (Exception e) {
		// Message will not contain BUNDLE_KEY_ACTION and will be dropped by scheduler
			Timber.e(e, "JSON serialization failed for message %m. Message will be dropped", m.getMessageId());
			return App.getMessageProcessor().onMessageDeliveryFailedFinal(messageId);
		}
	}



	@SuppressWarnings("ConstantConditions")
	private MqttMessage mqttMessageFromBundle(Bundle b) {
		MqttMessage  m = new MqttMessage();
		m.setPayload(b.getByteArray(MQTT_BUNDLE_KEY_MESSAGE_PAYLOAD));
		m.setQos(b.getInt(MQTT_BUNDLE_KEY_MESSAGE_QOS));
		m.setRetained(b.getBoolean(MQTT_BUNDLE_KEY_MESSAGE_RETAINED));
		return m;
	}

	@NonNull
	private Bundle mqttMessageToBundle(MessageBase m)  {
		Bundle b = new Bundle();
		b.putLong(Scheduler.BUNDLE_KEY_MESSAGE_ID, m.getMessageId());
		b.putString(Scheduler.BUNDLE_KEY_ACTION, Scheduler.ONEOFF_TASK_SEND_MESSAGE_MQTT);

		try {
			// Message properties
			b.putByteArray(MQTT_BUNDLE_KEY_MESSAGE_PAYLOAD, App.getParser().toJsonBytes(m));
			b.putString(MQTT_BUNDLE_KEY_MESSAGE_TOPIC, m.getTopic());
			b.putInt(MQTT_BUNDLE_KEY_MESSAGE_QOS, m.getQos());
			b.putBoolean(MQTT_BUNDLE_KEY_MESSAGE_RETAINED, m.getRetained());
		} catch (Exception e) {
			// Message will not contain BUNDLE_KEY_ACTION and will be dropped by scheduler
			Timber.e(e, "JSON serialization failed for message %m. Message will be dropped" ,m.getMessageId());
			return b;
		}
		return b;
	}

	@NonNull
	private Bundle mqttMessageToBundle(@NonNull MessageClear m) {
		Bundle b = mqttMessageToBundle(MessageBase.class.cast(m));
		b.putByteArray(MQTT_BUNDLE_KEY_MESSAGE_PAYLOAD, new byte[0]);
		b.putBoolean(MQTT_BUNDLE_KEY_MESSAGE_RETAINED, true);
		return b;
	}



	private static MessageProcessorEndpointMqtt instance;
	public static MessageProcessorEndpointMqtt getInstance() {
		if(instance == null)
			instance = new MessageProcessorEndpointMqtt();
		return instance;
	}

	private final MqttCallbackExtended iCallbackClient = new MqttCallbackExtended() {
		@Override
		public void connectComplete(boolean reconnect, String serverURI) {
			Timber.v("%s, serverUri:%s", reconnect, serverURI);
			onConnect();
		}

		@Override
		public void deliveryComplete(IMqttDeliveryToken token) {

		}

		@Override
		public void connectionLost(Throwable cause) {
			Timber.e(cause, "connectionLost error");
			App.getScheduler().cancelMqttPing();
            App.getScheduler().scheduleMqttReconnect();
			changeState(EndpointState.DISCONNECTED, new Exception(cause));
		}

		@Override
		public void messageArrived(String topic, MqttMessage message) throws Exception {
			try {
				MessageBase m = App.getParser().fromJson(message.getPayload());
				if (!m.isValidMessage()) {
					Timber.e("message failed validation");
					return;
				}

				m.setTopic(topic);
				m.setRetained(message.isRetained());
				m.setQos(message.getQos());
				App.getMessageProcessor().onMessageReceived(m);
			} catch (Exception e) {
				if (message.getPayload().length == 0) {
					Timber.v("clear message received: %s", topic);
					MessageClear m = new MessageClear();
					m.setTopic(topic.replace(MessageCard.BASETOPIC_SUFFIX, ""));
					App.getMessageProcessor().onMessageReceived(m);
				} else {
					Timber.e(e, "payload:%s ", new String(message.getPayload()));
				}
			}
		}
	};


	private boolean initClient() {
		if (this.mqttClient != null) {
			return true;
		}

		Timber.v("initializing new mqttClient");
		try {

			String prefix = "tcp";
			if (App.getPreferences().getTls()) {
				if (App.getPreferences().getWs()) {
					prefix = "wss";
				} else
					prefix = "ssl";
			} else {
				if (App.getPreferences().getWs())
					prefix = "ws";
			}

			String cid = App.getPreferences().getClientId();
            String connectString = prefix + "://" + App.getPreferences().getHost() + ":" + App.getPreferences().getPort();
			Timber.v("mode: " + App.getPreferences().getModeId());
			Timber.v("client id: " + cid);
			Timber.v("connect string: " + connectString);

			this.mqttClient = new CustomMqttClient(connectString, cid, new MqttClientMemoryPersistence());
			this.mqttClient.setCallback(iCallbackClient);
		} catch (Exception e) {
			Timber.e(e, "init failed");
			this.mqttClient = null;
			changeState(e);
            return false;
		}
        return true;
	}

	private int sendMessageConnectPressure = 0;

	@WorkerThread
	private boolean connect() {
		sendMessageConnectPressure++;
		boolean isUiThread = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? Looper.getMainLooper().isCurrentThread()
				: Thread.currentThread() == Looper.getMainLooper().getThread();

		if(isUiThread) {
			try {
				throw new Exception("BLOCKING CONNECT ON MAIN THREAD");
			} catch (Exception e) {
				Timber.e(e);
				e.printStackTrace();
			}
		} else {
			Timber.e("Thread: %s", Thread.currentThread());
		}

		if(isConnected()) {
			Timber.v("already connected");
			changeState(getState()); // Background service might be restarted and not get the connection state
			return true;
		}

		if(isConnecting()) {
			Timber.v("already connecting");
			return false;
		}

		if(!isConfigurationComplete()) {
			changeState(EndpointState.ERROR_CONFIGURATION);
			return false;
		}

		// Check if there is a data connection.
		if (!isOnline()) {
			changeState(EndpointState.ERROR_DATADISABLED);
			return false;
		}

		Timber.v("connecting on thread %s",  Thread.currentThread().getId());

        changeState(EndpointState.CONNECTING);

		if(!initClient()) {
            return false;
        }

		try {
			Timber.v("setting up connect options");
			 connectOptions = new MqttConnectOptions();
			if (App.getPreferences().getAuth()) {
				connectOptions.setPassword(App.getPreferences().getPassword().toCharArray());
				connectOptions.setUserName(App.getPreferences().getUsername());
			}

			connectOptions.setMqttVersion(App.getPreferences().getMqttProtocolLevel());

			if (App.getPreferences().getTls()) {
				String tlsCaCrt = App.getPreferences().getTlsCaCrtName();
				String tlsClientCrt = App.getPreferences().getTlsClientCrtName();

				SocketFactory.SocketFactoryOptions socketFactoryOptions = new SocketFactory.SocketFactoryOptions();

				if (tlsCaCrt.length() > 0) {
					try {
						socketFactoryOptions.withCaInputStream(App.getContext().openFileInput(tlsCaCrt));
					} catch (FileNotFoundException e) {
						e.printStackTrace();
					}
				}

				if (tlsClientCrt.length() > 0) {
					try {
						socketFactoryOptions.withClientP12InputStream(App.getContext().openFileInput(tlsClientCrt)).withClientP12Password(App.getPreferences().getTlsClientCrtPassword());
					} catch (FileNotFoundException e1) {
						e1.printStackTrace();
					}
				}



				connectOptions.setSocketFactory(new SocketFactory(socketFactoryOptions));
			}


            setWill(connectOptions);
			connectOptions.setKeepAliveInterval(App.getPreferences().getKeepalive());
			connectOptions.setConnectionTimeout(30);
			connectOptions.setCleanSession(App.getPreferences().getCleanSession());

			Timber.v("connecting sync");
			this.mqttClient.connect(connectOptions).waitForCompletion();
			App.getScheduler().scheduleMqttPing(connectOptions.getKeepAliveInterval());
			changeState(EndpointState.CONNECTED);

			sendMessageConnectPressure =0; // allow new connection attempts from sendMessage
			return true;

		} catch (Exception e) { // Catch paho and socket factory exceptions
			Log.e(TAG, e.toString());
            e.printStackTrace();
			changeState(e);
			return false;
		}
	}

	private void setWill(MqttConnectOptions m) {
        try {
            JSONObject lwt = new JSONObject();
            lwt.put("_type", "lwt");
            lwt.put("tst", (int) TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()));

            m.setWill(App.getPreferences().getPubTopicBase(), lwt.toString().getBytes(), 0, false);
        } catch(JSONException ignored) {}

	}

	private String getConnectionId() {
		return mqttClient.getCurrentServerURI()+"/"+ connectOptions.getUserName();
	}

	private void onConnect() {
		App.getScheduler().cancelMqttReconnect();
		// Check if we're connecting to the same broker that we were already connected to
		String connectionId = getConnectionId();
		if(lastConnectionId != null && !connectionId.equals(lastConnectionId)) {
			App.getEventBus().post(new Events.EndpointChanged());
			lastConnectionId = connectionId;
			Log.v(TAG, "lastConnectionId changed to: " + lastConnectionId);
		}

		List<String> topics = new ArrayList<>();
		String subTopicBase = App.getPreferences().getSubTopic();

		if(!App.getPreferences().getSub()) // Don't subscribe if base topic is invalid
			return;
		else if(subTopicBase.endsWith("#")) { // wildcard sub will match everything anyway
			topics.add(subTopicBase);
		} else {

			topics.add(subTopicBase);
			if(App.getPreferences().getInfo())
				topics.add(subTopicBase + App.getPreferences().getPubTopicInfoPart());

			topics.add(App.getPreferences().getPubTopicBase() + App.getPreferences().getPubTopicCommandsPart());
			topics.add(subTopicBase + App.getPreferences().getPubTopicEventsPart());
			topics.add(subTopicBase + App.getPreferences().getPubTopicWaypointsPart());


		}

		subscribe(topics.toArray(new String[topics.size()]));
	}



    private void subscribe(String[] topics) {
		if(!isConnected()) {
            Log.e(TAG, "subscribe when not connected");
            return;
        }
        for(String s : topics) {
            Log.v(TAG, "subscribe() - Will subscribe to: " + s);
        }
		try {
			int qos[] = getSubTopicsQos(topics);
			this.mqttClient.subscribe(topics, qos);
		} catch (Exception e) {
			e.printStackTrace();
		}
    }


	private int[] getSubTopicsQos(String[] topics) {
		int[] qos = new int[topics.length];
		Arrays.fill(qos, App.getPreferences().getSubQos());
		return qos;
	}

	@SuppressWarnings("unused")
	private void unsubscribe(String[] topics) {
		if(!isConnected()) {
			Log.e(TAG, "subscribe when not connected");
			return;
		}

		for(String s : topics) {
			Log.v(TAG, "unsubscribe() - Will unsubscribe from: " + s);
		}

		try {
			mqttClient.unsubscribe(topics);
		} catch (Exception e) {
			e.printStackTrace();
		}
    }


	private void disconnect(boolean fromUser) {

		Timber.v("disconnect. user:%s", fromUser);
		if (isConnecting()) {
            return;
        }

		try {
			if (isConnected()) {
				Log.v(TAG, "Disconnecting");
				this.mqttClient.disconnect(0);
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			this.mqttClient = null;

			if (fromUser)
				changeState(EndpointState.DISCONNECTED_USERDISCONNECT);
			else
				changeState(EndpointState.DISCONNECTED);
			App.getScheduler().cancelMqttPing();
			App.getScheduler().cancelMqttReconnect();

		}
	}



	public void reconnect() {
		disconnect(false);
		connect();
	}

	@Override
	public void disconnect() {
		disconnect(true);
	}

	@Override
	public void onEnterForeground() {
		checkConnection();
	}

	@Override
	public boolean isConfigurationComplete() {
		return !App.getPreferences().getHost().trim().equals("") && !App.getPreferences().getUsername().trim().equals("") && (!App.getPreferences().getAuth() || !App.getPreferences().getPassword().trim().equals(""));
	}

	@WorkerThread
	boolean checkConnection() {
		if(isConnected()) {
			return true;
		} else {
			connect();
			return false;
		}
	}

	private void changeState(Exception e) {
		changeState(EndpointState.ERROR, e);
	}

	private void changeState(EndpointState newState) {
		//Reduce unnecessary work caused by state updates to the same state
		if(state == newState)
			return;

		state = newState;
		getMessageProcessor().onEndpointStateChanged(newState);
	}

	private void changeState(EndpointState newState, Exception e) {
		state = newState;
		getMessageProcessor().onEndpointStateChanged(newState.setError(e));
	}

	private MessageProcessor getMessageProcessor() {
		return App.getMessageProcessor();
	}

	private boolean isOnline() {
		ConnectivityManager cm = (ConnectivityManager) App.getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo netInfo = cm.getActiveNetworkInfo();

        if(netInfo != null && netInfo.isAvailable() && netInfo.isConnected()) {
            return true;
        } else {
            Log.e(TAG, "isOnline == false. activeNetworkInfo: "+ (netInfo != null) +", available:" + (netInfo != null && netInfo.isAvailable()) + ", connected:" + (netInfo != null && netInfo.isConnected()));
            return false;
        }
	}

	private boolean isConnected() {
		return this.mqttClient != null && this.mqttClient.isConnected();
	}

	private boolean isConnecting() {
		return (this.mqttClient != null) && (state == EndpointState.CONNECTING);
	}

	public static EndpointState getState() {
		return state;
	}


	@SuppressWarnings("UnusedParameters")
	@Subscribe
	public void onEvent(Events.EndpointChanged e) {
		reconnect();
	}

	public void processOutgoingMessage(MessageBase message) {
		message.setTopic(App.getPreferences().getPubTopicBase());
		sendMessage(message);
		//schedulesMessage(mqttMessageToBundle(message));
	}

	@Override
	public void processOutgoingMessage(MessageCmd message) {
		message.setTopic(App.getPreferences().getPubTopicCommands());
		sendMessage(message);
		//scheduleMessage(mqttMessageToBundle(message));
	}

	@Override
	public void processOutgoingMessage(MessageEvent message) {
		message.setTopic(App.getPreferences().getPubTopicEvents());
		sendMessage(message);
		//scheduleMessage(mqttMessageToBundle(message));
	}

	@Override
	public void processOutgoingMessage(MessageLocation message) {
		message.setTopic(App.getPreferences().getPubTopicLocations());
		message.setQos(App.getPreferences().getPubQosLocations());
		message.setRetained(App.getPreferences().getPubRetainLocations());
		sendMessage(message);
		//scheduleMessage(mqttMessageToBundle(message));
	}

	@Override
	public void processOutgoingMessage(MessageTransition message) {
		message.setTopic(App.getPreferences().getPubTopicEvents());
		message.setQos(App.getPreferences().getPubQosEvents());
		message.setRetained(Preferences.getPubRetainEvents());
		sendMessage(message);
		//scheduleMessage(mqttMessageToBundle(message));
	}

	@Override
	public void processOutgoingMessage(MessageWaypoint message) {
		message.setTopic(App.getPreferences().getPubTopicWaypoints());
		message.setQos(Preferences.getPubQosWaypoints());
		message.setRetained(Preferences.getPubRetainWaypoints());
		sendMessage(message);
		//scheduleMessage(mqttMessageToBundle(message));
	}

	@Override
	public void processOutgoingMessage(MessageWaypoints message) {
		message.setTopic(App.getPreferences().getPubTopicWaypoints());
		message.setQos(App.getPreferences().getPubQosWaypoints());
		message.setRetained(App.getPreferences().getPubRetainWaypoints());
		sendMessage(message);
		//scheduleMessage(mqttMessageToBundle(message));
	}

	@Override
	public void processOutgoingMessage(MessageClear message) {
		message.setRetained(true);
		sendMessage(message);

		//scheduleMessage(mqttMessageToBundle(message));

		message.setTopic(message.getTopic()+MessageCard.BASETOPIC_SUFFIX);
		sendMessage(message);
		//scheduleMessage(mqttMessageToBundle(message));
	}

	@Override
	public void onDestroy() {
		disconnect(false);
		App.getScheduler().cancelMqttTasks();;
	}

	@Override
	public void onCreateFromProcessor() {
		App.getScheduler().scheduleMqttReconnect();
		//connect();
	}



	private void scheduleMessage(Bundle b) {
			//if(App.isInForeground())
			//	sendMessage(b);
			//else
			//	App.getScheduler().scheduleMessage(b);
	}


	private static final class MqttClientMemoryPersistence implements MqttClientPersistence {
		private static Hashtable<String, MqttPersistable> data;

		@Override
		public void open(String s, String s2) throws MqttPersistenceException {
			if(data == null) {
				data = new Hashtable<>();
			}
		}

		@SuppressWarnings("unused")
		private Integer getSize(){
			return data.size();
		}

		@Override
		public void close() throws MqttPersistenceException {

		}

		@Override
		public void put(String key, MqttPersistable persistable) throws MqttPersistenceException {
			data.put(key, persistable);
		}

		@Override
		public MqttPersistable get(String key) throws MqttPersistenceException {
			return data.get(key);
		}

		@Override
		public void remove(String key) throws MqttPersistenceException {
			data.remove(key);
		}

		@Override
		public Enumeration keys() throws MqttPersistenceException {
			return data.keys();
		}

		@Override
		public void clear() throws MqttPersistenceException {
			data.clear();
		}

		@Override
		public boolean containsKey(String key) throws MqttPersistenceException {
			return data.containsKey(key);
		}
	}

	private static final class CustomMqttClient extends MqttAsyncClient {

		CustomMqttClient(String serverURI, String clientId, MqttClientPersistence persistence) throws MqttException {
			super(serverURI, clientId, persistence);
		}

		void ping() {
			if(comms != null)
				comms.checkForActivity();
		}
	}
}

