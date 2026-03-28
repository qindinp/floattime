package com.floattime.app

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException

/**
 * 手写 IPrivilegedService 接口 + Stub，替代 AIDL 生成。
 *
 * AIDL 编译在 Capacitor + AGP 8.x 环境下无法自动触发，
 * 故直接用 Kotlin 实现等价的 Binder 接口。
 */
interface IPrivilegedService : IInterface {

    @Throws(RemoteException::class)
    fun setPackageNetworkingEnabled(uid: Int, enabled: Boolean): Boolean

    @Throws(RemoteException::class)
    fun ping(): Boolean

    abstract class Stub : Binder(), IPrivilegedService {

        companion object {
            private const val DESCRIPTOR = "com.floattime.app.IPrivilegedService"

            // Transaction codes (must match AIDL convention: method index starts at 1)
            private const val TRANSACTION_setPackageNetworkingEnabled = IBinder.FIRST_CALL_TRANSACTION + 0
            private const val TRANSACTION_ping = IBinder.FIRST_CALL_TRANSACTION + 1

            fun asInterface(obj: IBinder?): IPrivilegedService? {
                obj ?: return null
                val iin = obj.queryLocalInterface(DESCRIPTOR)
                return if (iin is IPrivilegedService) iin else Proxy(obj)
            }
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR)
                    true
                }
                TRANSACTION_setPackageNetworkingEnabled -> {
                    data.enforceInterface(DESCRIPTOR)
                    val uid = data.readInt()
                    val enabled = data.readInt() != 0
                    val result = setPackageNetworkingEnabled(uid, enabled)
                    reply?.writeNoException()
                    reply?.writeInt(if (result) 1 else 0)
                    true
                }
                TRANSACTION_ping -> {
                    data.enforceInterface(DESCRIPTOR)
                    val pingResult = ping()
                    reply?.writeNoException()
                    reply?.writeInt(if (pingResult) 1 else 0)
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }

        private class Proxy(private val mRemote: IBinder) : IPrivilegedService {
            override fun asBinder(): IBinder = mRemote

            @Throws(RemoteException::class)
            override fun setPackageNetworkingEnabled(uid: Int, enabled: Boolean): Boolean {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                return try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeInt(uid)
                    data.writeInt(if (enabled) 1 else 0)
                    mRemote.transact(TRANSACTION_setPackageNetworkingEnabled, data, reply, 0)
                    reply.readException()
                    reply.readInt() != 0
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }

            @Throws(RemoteException::class)
            override fun ping(): Boolean {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                return try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    mRemote.transact(TRANSACTION_ping, data, reply, 0)
                    reply.readException()
                    reply.readInt() != 0
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
        }
    }
}
