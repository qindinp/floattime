package com.floattime.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * 手写 IPrivilegedService 接口 + Stub，替代 AIDL 生成。
 *
 * AIDL 编译在 Capacitor + AGP 8.x 环境下无法自动触发，
 * 故直接用 Java 实现等价的 Binder 接口。
 */
public interface IPrivilegedService extends IInterface {

    boolean setPackageNetworkingEnabled(int uid, boolean enabled) throws RemoteException;
    boolean ping() throws RemoteException;

    abstract class Stub extends Binder implements IPrivilegedService {

        private static final String DESCRIPTOR = "com.floattime.app.IPrivilegedService";

        // Transaction codes (must match AIDL convention: method index starts at 1)
        private static final int TRANSACTION_setPackageNetworkingEnabled = IBinder.FIRST_CALL_TRANSACTION + 0;
        private static final int TRANSACTION_ping = IBinder.FIRST_CALL_TRANSACTION + 1;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        /**
         * 将 IBinder 转换为 IPrivilegedService 接口
         */
        public static IPrivilegedService asInterface(IBinder obj) {
            if (obj == null) return null;
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IPrivilegedService) {
                return (IPrivilegedService) iin;
            }
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case INTERFACE_TRANSACTION:
                    reply.writeString(DESCRIPTOR);
                    return true;
                case TRANSACTION_setPackageNetworkingEnabled:
                    data.enforceInterface(DESCRIPTOR);
                    int uid = data.readInt();
                    boolean enabled = data.readInt() != 0;
                    boolean result = setPackageNetworkingEnabled(uid, enabled);
                    reply.writeNoException();
                    reply.writeInt(result ? 1 : 0);
                    return true;
                case TRANSACTION_ping:
                    data.enforceInterface(DESCRIPTOR);
                    boolean pingResult = ping();
                    reply.writeNoException();
                    reply.writeInt(pingResult ? 1 : 0);
                    return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static class Proxy implements IPrivilegedService {
            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            @Override
            public boolean setPackageNetworkingEnabled(int uid, boolean enabled) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeInt(uid);
                    data.writeInt(enabled ? 1 : 0);
                    mRemote.transact(TRANSACTION_setPackageNetworkingEnabled, data, reply, 0);
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }

            @Override
            public boolean ping() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_ping, data, reply, 0);
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }
        }
    }
}
