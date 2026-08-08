package com.yanyue.rag.application.chat.v5;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class KeywordQueryPolicyV5Test {
    @Test
    void 应移除业务背景和抽象定位词但保留核心复合词() {
        assertEquals("应用场景", KeywordQueryPolicyV5.normalize("应用场景 企业操作系统"));
        assertEquals("部署落地方式", KeywordQueryPolicyV5.normalize("部署落地方式 能力定位"));
        assertEquals("容器镜像 镜像构建", KeywordQueryPolicyV5.normalize("容器镜像 镜像构建 核心定位"));
    }

    @Test
    void 应保留显式同义词或查询结构() {
        assertEquals("安装方式 OR 部署方式",
                KeywordQueryPolicyV5.normalize("安装方式 OR 部署方式 相关资料"));
    }

    @Test
    void 应把日常治理包装词规范为管理术语和实体宽锚点() {
        assertEquals("管理虚拟机 OR 虚拟机", KeywordQueryPolicyV5.normalize("日常治理虚拟机"));
        assertEquals("管理设备 OR 设备", KeywordQueryPolicyV5.normalize("日常治理设备 核心定位"));
        assertEquals("管理服务 OR 服务", KeywordQueryPolicyV5.normalize("日常治理服务 相关资料"));
    }

    @Test
    void 应保持产品版本和已有复合词不变() {
        assertEquals("adoctor-check 5.2", KeywordQueryPolicyV5.normalize("adoctor-check 5.2"));
        assertEquals("容器镜像 镜像构建", KeywordQueryPolicyV5.normalize("容器镜像 镜像构建"));
    }

    @Test
    void v7应规范化已有Artifact证明的高置信企业术语() {
        assertEquals("使用方法 OR 操作方法",
                KeywordQueryPolicyV5.normalizeV7("实际操作方法", "实际操作方法中的文档约束与做法"));
        assertEquals("CRI接口 OR CRI",
                KeywordQueryPolicyV5.normalizeV7("cri调用边界", "CRI调用边界中的描述"));
        assertEquals("虚拟机配置 OR 虚拟机",
                KeywordQueryPolicyV5.normalizeV7("虚拟机可调选项设置", "虚拟机可调选项设置"));
        assertEquals("故障诊断模块 OR 故障诊断",
                KeywordQueryPolicyV5.normalizeV7("异常根因定位模块", "异常根因定位模块能力定位"));
        assertEquals("安装配置 OR 部署配置",
                KeywordQueryPolicyV5.normalizeV7("可调选项 OR 参数设置", "部署时可调选项设置"));
        assertEquals("容器镜像 镜像构建",
                KeywordQueryPolicyV5.normalizeV7("隔离运行单元镜像 镜像构建", "隔离运行单元镜像构建"));
        assertEquals("获取安装源 OR 安装源",
                KeywordQueryPolicyV5.normalizeV7("获取部署落地源", "快速入门中的获取部署落地源"));
        assertEquals("安装源 OR 软件源",
                KeywordQueryPolicyV5.normalizeV7("部署落地源", "准备部署落地源"));
        assertEquals("安装方式 OR 部署方式",
                KeywordQueryPolicyV5.normalizeV7("部署落地方式", "部署落地方式能力定位"));
        assertEquals("安装方法 OR 部署方法",
                KeywordQueryPolicyV5.normalizeV7("部署落地方法", "部署落地方法"));
        assertEquals("安装指导 OR 部署指导",
                KeywordQueryPolicyV5.normalizeV7("部署落地指导", "部署落地指导"));
    }

    @Test
    void v7规范化不改变v5历史入口或已有显式查询() {
        assertEquals("实际操作方法", KeywordQueryPolicyV5.normalize("实际操作方法"));
        assertEquals("安装方式 OR 部署方式",
                KeywordQueryPolicyV5.normalizeV7("安装方式 OR 部署方式", "部署方式"));
        assertEquals("adoctor-check 5.2",
                KeywordQueryPolicyV5.normalizeV7("adoctor-check 5.2", "依赖组件安装"));
        assertEquals("部署落地",
                KeywordQueryPolicyV5.normalizeV7("部署落地", "部署落地"));
    }

    @Test
    void v8应保留部署信息类型锚点避免不同Goal碰撞() {
        assertEquals("获取安装源 OR 安装源 OR 快速入门 OR 获取部署落地源",
                KeywordQueryPolicyV5.normalizeV8("获取部署落地源", "快速入门中的获取部署落地源"));
        assertEquals("安装方式 OR 部署方式 OR 部署落地方式",
                KeywordQueryPolicyV5.normalizeV8("部署落地方式", "部署落地方式能力定位"));
        assertEquals("使用方法 OR 操作方法 OR 实际操作方法",
                KeywordQueryPolicyV5.normalizeV8("实际操作方法", "实际操作方法中的文档约束与做法"));
        assertEquals("部署方式 OR 部署模式 OR 安装方式 OR 部署落地方式",
                KeywordQueryPolicyV5.normalizeV8("部署方式 OR 部署模式",
                        "部署落地方式能力定位中的部署落地方式能力定位"));
        assertEquals("口令策略 OR 密码策略 OR 账户口令",
                KeywordQueryPolicyV5.normalizeV8("口令策略 OR 密码策略", "账户口令中的约束与做法"));
        assertEquals("安装配置 OR 部署配置 OR 部署落地可调选项设置",
                KeywordQueryPolicyV5.normalizeV8("部署 配置项 注意事项",
                        "部署落地可调选项设置中的注意事项"));
    }

    @Test
    void v8不应把场景背景或抽象定位包装误认为文档标题() {
        assertEquals("使用方法 OR 操作方法 OR 实际操作方法",
                KeywordQueryPolicyV5.normalizeV8(
                        "变更窗口 操作方法 OR 在企业变更窗口 OR 实际操作方法",
                        "在企业变更窗口中，实际操作方法包含哪些约束与做法"));
        assertEquals("安装方式 OR 部署方式 OR 部署落地方式",
                KeywordQueryPolicyV5.normalizeV8("部署落地方式",
                        "部署落地方式能力定位中的部署落地方式能力定位"));
        assertEquals("企业操作系统 OR 应用场景",
                KeywordQueryPolicyV5.normalizeV8("企业操作系统", "应用场景"));
        assertEquals("需要完成应用场景 OR 应用场景",
                KeywordQueryPolicyV5.normalizeV8("需要完成应用场景", "应用场景"));
    }

    @Test
    void v8应让Goal和语义检索共享高置信规范术语() {
        assertEquals("快速入门中的获取安装源和软件源",
                KeywordQueryPolicyV5.canonicalGoalV8("快速入门中的获取部署落地源"));
        assertEquals("安装方式介绍中的安装方式介绍",
                KeywordQueryPolicyV5.canonicalGoalV8("部署落地方式能力定位中的部署落地方式能力定位"));
        assertEquals("使用方法和操作方法中的文档约束与做法",
                KeywordQueryPolicyV5.canonicalGoalV8("实际操作方法中的文档约束与做法"));
        assertEquals("管理设备中的功能与作用",
                KeywordQueryPolicyV5.canonicalGoalV8("日常治理设备中的核心定位"));
        assertEquals("配置网络中的nmcli功能与作用",
                KeywordQueryPolicyV5.canonicalGoalV8("可调选项设置网络中的nmcli能力定位"));
        assertEquals("使用GCC编译中的使用GCC编译",
                KeywordQueryPolicyV5.canonicalGoalV8("实际操作GCC编译中的实际操作GCC编译"));
        assertEquals("安装配置中的注意事项",
                KeywordQueryPolicyV5.canonicalGoalV8("部署落地可调选项设置中的注意事项"));
        assertEquals("管理系统资源中的功能与作用",
                KeywordQueryPolicyV5.canonicalGoalV8("日常治理系统资源中的核心定位"));
        assertEquals("安装与配置中的安装部署方法",
                KeywordQueryPolicyV5.canonicalGoalV8("部署落地与可调选项设置中的部署落地方法"));
        assertEquals("容器资源管理中的用法",
                KeywordQueryPolicyV5.canonicalGoalV8("隔离运行单元资源日常治理中的用法"));
    }

    @Test
    void v8应把稳定标题保留为独立OR分支而不是受主题词约束() {
        assertEquals("系统服务 约束 OR 系统服务",
                KeywordQueryPolicyV5.normalizeV8("系统服务 约束", "系统服务中的约束与做法"));
        assertEquals("最佳实践 定位 OR 最佳实践",
                KeywordQueryPolicyV5.normalizeV8("最佳实践 定位", "最佳实践中的功能与作用"));
        assertEquals("管理系统资源 OR 系统资源",
                KeywordQueryPolicyV5.normalizeV8("管理系统资源 OR 系统资源", "管理系统资源中的功能与作用"));
    }
}
