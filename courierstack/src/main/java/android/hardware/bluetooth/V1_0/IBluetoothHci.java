package android.hardware.bluetooth.V1_0;

import android.os.HwBinder;
import android.os.HwParcel;
import android.os.IHwBinder;
import android.os.IHwInterface;
import android.os.RemoteException;
import java.util.ArrayList;
import java.util.NoSuchElementException;

public interface IBluetoothHci extends IHwInterface {
    public static final String kInterfaceName = "android.hardware.bluetooth@1.0::IBluetoothHci";

    public static IBluetoothHci getService(boolean retry) throws RemoteException, NoSuchElementException {
        return getService("default", retry);
    }

    public static IBluetoothHci getService(String serviceName, boolean retry) throws RemoteException, NoSuchElementException {
        IHwBinder binder = HwBinder.getService(kInterfaceName, serviceName, retry);
        if (binder == null) {
            throw new NoSuchElementException("No service " + serviceName);
        }
        IHwInterface iface = binder.queryLocalInterface(kInterfaceName);
        if (iface instanceof IBluetoothHci) {
            return (IBluetoothHci) iface;
        }
        return new Proxy(binder);
    }

    void initialize(IBluetoothHciCallbacks callbacks) throws RemoteException;
    void sendHciCommand(ArrayList<Byte> command) throws RemoteException;
    void sendAclData(ArrayList<Byte> data) throws RemoteException;
    void sendScoData(ArrayList<Byte> data) throws RemoteException;
    void close() throws RemoteException;

    @Override
    IHwBinder asBinder();

    public static final class Proxy implements IBluetoothHci {
        private IHwBinder mRemote;

        public Proxy(IHwBinder remote) {
            mRemote = remote;
        }

        @Override
        public IHwBinder asBinder() {
            return mRemote;
        }

        @Override
        public void initialize(IBluetoothHciCallbacks callbacks) throws RemoteException {
            HwParcel request = new HwParcel();
            request.writeInterfaceToken(kInterfaceName);
            request.writeStrongBinder(callbacks == null ? null : callbacks.asBinder());
            HwParcel reply = new HwParcel();
            mRemote.transact(1, request, reply, 0);
            reply.verifySuccess();
            reply.releaseTemporaryStorage();
        }

        @Override
        public void sendHciCommand(ArrayList<Byte> command) throws RemoteException {
            HwParcel request = new HwParcel();
            request.writeInterfaceToken(kInterfaceName);
            request.writeInt8Vector(command);
            HwParcel reply = new HwParcel();
            mRemote.transact(2, request, reply, 0);
            reply.verifySuccess();
            reply.releaseTemporaryStorage();
        }

        @Override
        public void sendAclData(ArrayList<Byte> data) throws RemoteException {
            HwParcel request = new HwParcel();
            request.writeInterfaceToken(kInterfaceName);
            request.writeInt8Vector(data);
            HwParcel reply = new HwParcel();
            mRemote.transact(3, request, reply, 0);
            reply.verifySuccess();
            reply.releaseTemporaryStorage();
        }

        @Override
        public void sendScoData(ArrayList<Byte> data) throws RemoteException {
            HwParcel request = new HwParcel();
            request.writeInterfaceToken(kInterfaceName);
            request.writeInt8Vector(data);
            HwParcel reply = new HwParcel();
            mRemote.transact(4, request, reply, 0);
            reply.verifySuccess();
            reply.releaseTemporaryStorage();
        }

        @Override
        public void close() throws RemoteException {
            HwParcel request = new HwParcel();
            request.writeInterfaceToken(kInterfaceName);
            HwParcel reply = new HwParcel();
            mRemote.transact(5, request, reply, 0);
            reply.verifySuccess();
            reply.releaseTemporaryStorage();
        }
    }
}
