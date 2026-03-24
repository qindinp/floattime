package com.floattime.app;

interface IPrivilegedService {
    boolean setPackageNetworkingEnabled(int uid, boolean enabled);
    boolean ping();
}
