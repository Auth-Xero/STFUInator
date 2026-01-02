package android.hardware.bluetooth.V1_0;

import android.os.HwBinder;
import android.os.HwParcel;
import android.os.IHwBinder;
import android.os.IHwInterface;
import android.os.RemoteException;
import java.util.ArrayList;

public interface IBluetoothHciCallbacks extends IHwInterface {
    public static final String kInterfaceName = "android.hardware.bluetooth@1.0::IBluetoothHciCallbacks";

    void initializationComplete(int status) throws RemoteException;
    void hciEventReceived(ArrayList<Byte> event) throws RemoteException;
    void aclDataReceived(ArrayList<Byte> data) throws RemoteException;
    void scoDataReceived(ArrayList<Byte> data) throws RemoteException;

    public abstract static class Stub extends HwBinder implements IBluetoothHciCallbacks {
        @Override
        public IHwBinder asBinder() {
            return this;
        }

        public void onTransact(int code, HwParcel request, HwParcel reply, int flags) throws RemoteException {
            switch (code) {
                case 1: {
                    request.enforceInterface(kInterfaceName);
                    int status = request.readInt32();
                    initializationComplete(status);
                    reply.writeStatus(HwParcel.STATUS_SUCCESS);
                    reply.send();
                    break;
                }
                case 2: {
                    request.enforceInterface(kInterfaceName);
                    ArrayList<Byte> event = request.readInt8Vector();
                    hciEventReceived(event);
                    reply.writeStatus(HwParcel.STATUS_SUCCESS);
                    reply.send();
                    break;
                }
                case 3: {
                    request.enforceInterface(kInterfaceName);
                    ArrayList<Byte> data = request.readInt8Vector();
                    aclDataReceived(data);
                    reply.writeStatus(HwParcel.STATUS_SUCCESS);
                    reply.send();
                    break;
                }
                case 4: {
                    request.enforceInterface(kInterfaceName);
                    ArrayList<Byte> data = request.readInt8Vector();
                    scoDataReceived(data);
                    reply.writeStatus(HwParcel.STATUS_SUCCESS);
                    reply.send();
                    break;
                }
            }
        }
    }
}
