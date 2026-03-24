// IShellService.aidl
package com.floattime.app;

/**
 * Shizuku UserService 接口
 * 用于在特权进程中执行 shell 命令
 */
interface IShellService {
    /**
     * 执行 shell 命令并返回输出
     */
    String execute(String command);
    
    /**
     * 销毁服务
     */
    void destroy();
}
