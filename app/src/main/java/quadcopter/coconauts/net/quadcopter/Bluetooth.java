
package quadcopter.coconauts.net.quadcopter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

public class Bluetooth {

	private static final String TAG = "BluetoothService";

	private static final String NAME = "SendBoost";

	private static final UUID MY_UUID = UUID.fromString("0001101-0000-1000-8000-00805F9B34FB");

    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;

	private final BluetoothAdapter mAdapter;
	private final Handler mHandler;
	private AcceptThread mAcceptThread;
	private ConnectThread mConnectThread;
	private ConnectedThread mConnectedThread;
	private int mState;

	public static final int STATE_NONE = 0;
	public static final int STATE_LISTEN = 1;
	public static final int STATE_CONNECTING = 2;
	public static final int STATE_CONNECTED = 3;


	public Bluetooth(Context context, Handler handler) {

        mAdapter = BluetoothAdapter.getDefaultAdapter();
		mState = STATE_NONE;
		mHandler = handler;
	}

	private synchronized void setState(int state) {

		mState = state;

		mHandler.obtainMessage(MESSAGE_STATE_CHANGE, state, -1)
				.sendToTarget();
	}

	public synchronized int getState() {
		return mState;
	}

	public synchronized void start() {

		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}

		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		setState(STATE_LISTEN);

		if (mAcceptThread == null) {
			mAcceptThread = new AcceptThread();
			mAcceptThread.start();
		}
	}

	private synchronized void connect(BluetoothDevice device) {

		if (mState == STATE_CONNECTING) {
			if (mConnectThread != null) {
				mConnectThread.cancel();
				mConnectThread = null;
			}
		}

		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		mConnectThread = new ConnectThread(device);
		mConnectThread.start();
		setState(STATE_CONNECTING);
	}

	public synchronized void connected(BluetoothSocket socket,
			BluetoothDevice device, final String socketType) {

		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}

		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		if (mAcceptThread != null) {
			mAcceptThread.cancel();
			mAcceptThread = null;
		}

		mConnectedThread = new ConnectedThread(socket, socketType);
		mConnectedThread.start();

		Message msg = mHandler.obtainMessage(MESSAGE_DEVICE_NAME);
		Bundle bundle = new Bundle();
		bundle.putString("Connected", device.getName());
		msg.setData(bundle);
		mHandler.sendMessage(msg);

		setState(STATE_CONNECTED);
	}

	public synchronized void stop() {

		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}

		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}

		if (mAcceptThread != null) {
			mAcceptThread.cancel();
			mAcceptThread = null;
		}
		setState(STATE_NONE);
	}

	private void write(byte[] out) {

		ConnectedThread r;

		synchronized (this) {
			if (mState != STATE_CONNECTED) return;
			r = mConnectedThread;
		}

		r.write(out);
	}

	private void connectionFailed() {

		Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
		Bundle bundle = new Bundle();
		bundle.putString("Toast", "Unable to connect device");
		msg.setData(bundle);
		mHandler.sendMessage(msg);

		Bluetooth.this.start();
	}

	private void connectionLost() {

		Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
		Bundle bundle = new Bundle();
		bundle.putString("Toast", "Device connection was lost");
		msg.setData(bundle);
		mHandler.sendMessage(msg);

		Bluetooth.this.start();
	}

	private class AcceptThread extends Thread {

		private final BluetoothServerSocket mmServerSocket;
		private String mSocketType;

		public AcceptThread() {
			BluetoothServerSocket tmp = null;

			try {
				tmp = mAdapter
						.listenUsingRfcommWithServiceRecord(NAME, MY_UUID);
			} catch (IOException e) {
				Log.e(TAG, "Socket Type: " + mSocketType + "listen() failed", e);
			}
			mmServerSocket = tmp;
		}

		public void run() {

			setName("AcceptThread" + mSocketType);

			BluetoothSocket socket = null;

			while (mState != STATE_CONNECTED) {

                try {
					socket = mmServerSocket.accept();
				} catch (IOException e) {
					Log.e(TAG, "Socket Type: " + mSocketType
							+ "accept() failed", e);
					break;
				}

				if (socket != null) {

                    synchronized (Bluetooth.this) {

                        switch (mState) {
                            case STATE_LISTEN:
                            case STATE_CONNECTING:
                                connected(socket, socket.getRemoteDevice(),
                                        mSocketType);
                                break;
                            case STATE_NONE:
                            case STATE_CONNECTED:
                                try {
                                    socket.close();
                                } catch (IOException e) {
                                    Log.e(TAG, "Could not close unwanted socket", e);
                                }
                                break;
						}
					}
				}
			}
		}

		public void cancel() {

			try {
				mmServerSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "Socket Type" + mSocketType
						+ "close() of server failed", e);
			}
		}
	}

	private class ConnectThread extends Thread {

        private final BluetoothSocket mmSocket;
		private final BluetoothDevice mmDevice;
		private String mSocketType;

		public ConnectThread(BluetoothDevice device) {

            mmDevice = device;
			BluetoothSocket tmp = null;

			try {
				tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
			} catch (IOException e) {
				Log.e(TAG, "Socket Type: " + mSocketType + "create() failed", e);
			}
			mmSocket = tmp;
		}

		public void run() {

			setName("ConnectThread" + mSocketType);

			mAdapter.cancelDiscovery();

			try {
				mmSocket.connect();

			} catch (IOException e) {

                try {
					mmSocket.close();
				} catch (IOException e2) {
					Log.e(TAG, "unable to close() " + mSocketType
							+ " socket during connection failure", e2);
				}
				connectionFailed();
				return;
			}

			synchronized (Bluetooth.this) {
				mConnectThread = null;
			}

			connected(mmSocket, mmDevice, mSocketType);
		}

		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "close() of connect " + mSocketType
						+ " socket failed", e);
			}
		}
	}

	private class ConnectedThread extends Thread {

        private final BluetoothSocket mmSocket;
		private final InputStream mmInStream;
		private final OutputStream mmOutStream;

		public ConnectedThread(BluetoothSocket socket, String socketType) {
			mmSocket = socket;
			InputStream tmpIn = null;
			OutputStream tmpOut = null;

			try {
				tmpIn = socket.getInputStream();
				tmpOut = socket.getOutputStream();
			} catch (IOException e) {
				Log.e(TAG, "Temp sockets not created", e);
			}

			mmInStream = tmpIn;
			mmOutStream = tmpOut;
		}

		public void run() {

			byte[] buffer = new byte[1024];
			int bytes;

			while (true) {
				try {
					bytes = mmInStream.read(buffer);
					Log.d(TAG, "Message bytes " + bytes);
					Log.d(TAG, "Message string bytes " + String.valueOf(bytes));
					Log.d(TAG, "Message buffer " + new String(buffer));
					mHandler.obtainMessage(MESSAGE_READ, bytes,
							-1, buffer).sendToTarget();
				} catch (IOException e) {
					connectionLost(); // Disconnected.
					Bluetooth.this.start();
					break;
				}
			}
		}

		public void write(byte[] buffer) {
			try {
				mmOutStream.write(buffer);

				mHandler.obtainMessage(MESSAGE_WRITE, -1, -1,
						buffer).sendToTarget();
			} catch (IOException e) {
				Log.e(TAG, "Exception during write", e);
			}
		}

		public void cancel() {
			try {
				mmSocket.close();
			} catch (IOException e) {
				Log.e(TAG, "close() of connect socket failed", e);
			}
		}
	}

    public void sendMessage(String message) {

        if (this.getState() != Bluetooth.STATE_CONNECTED)
            return; // Bluetooth is not connected.

        if (message.length() > 0) {
            char EOT = (char)3 ;
            byte[] send = (message + EOT).getBytes();
            this.write(send);
        }
    }

    public void connectDevice(String deviceName) {

        String address = null;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

		for(BluetoothDevice d: adapter.getBondedDevices()){
            if (d.getName().equals(deviceName)) address = d.getAddress();
        }

        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            this.connect(device);
        } catch (Exception e){
            Log.e("Unable to connect: "+ address,e.getMessage());
        }
    }

}
