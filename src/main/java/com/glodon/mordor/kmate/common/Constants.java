package com.glodon.mordor.kmate.common;

/**
 * 通用工具和常量。
 *
 * 当前是占位类——所有常量目前都散落在各包自己的类里(见 Diag 的日志路径、
 * ChatHistory 的 MEMORY_CAP / PAGE_SIZE 等)。这里保留这个类是为了未来集中
 * 维护"跨模块共用的字符串/数字"(如用户提示语、端口、文件路径默认值)。
 *
 * 私有构造器 + final 类 = 禁止外部实例化,只用作命名空间。
 */
public class Constants {
    // 工具类:私有构造器阻止 new Constants();final 类阻止子类化。
    private Constants() {}
}
