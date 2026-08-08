package com.yanyue.rag.application.chat.v5;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

final class KeywordQueryPolicyV5 {
    private static final Set<String> GENERIC_UNITS = Set.of(
            "企业操作系统", "主机运维", "基础设施治理", "相关资料", "资料",
            "核心定位", "能力定位", "关键条件", "条件", "边界", "做法",
            "具体要求", "操作步骤", "步骤", "限制", "参数", "要求");

    private KeywordQueryPolicyV5() {
    }

    static String normalize(String query) {
        return normalize(query, null, false);
    }

    static String normalizeV7(String query, String goalQuestion) {
        return normalize(query, goalQuestion, true);
    }

    /**
     * v8 keeps the v7 high-confidence canonical terms while retaining the
     * original information-type anchor when a rewrite would otherwise make
     * two distinct Goals indistinguishable (for example, source vs method).
     */
    public static String normalizeV8(String query, String goalQuestion) {
        var normalized = normalize(query, goalQuestion, true);
        var explicitGoalAnchor = conciseGoalAnchor(goalQuestion);
        if (!explicitGoalAnchor.isBlank() && !containsAlternative(normalized, explicitGoalAnchor)) {
            normalized = normalized + " OR " + explicitGoalAnchor;
        }
        var compact = ((query == null ? "" : query) + " "
                + (goalQuestion == null ? "" : goalQuestion))
                .replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (compact.contains("部署落地可调选项设置")) {
            normalized = "安装配置 OR 部署配置";
        }
        var titleAnchor = extractTitleAnchor(goalQuestion);
        // websearch_to_tsquery treats spaces inside one branch as AND. Merely
        // containing the title in a longer branch (for example, "系统服务 约束")
        // can therefore filter out the exact document. Keep an unconstrained
        // title branch for v8 whenever the Goal exposes a stable title anchor.
        if (!titleAnchor.isBlank() && !containsAlternative(normalized, titleAnchor)) {
            normalized = normalized + " OR " + titleAnchor;
        }
        if (compact.contains("获取部署落地源") && !normalized.contains("获取安装源")) {
            normalized = normalized + " OR 获取安装源";
        }
        if (compact.contains("部署落地方式") && !normalized.contains("安装方式")) {
            normalized = normalized + " OR 安装方式";
        }
        if (compact.contains("实际操作方法") && !normalized.contains("使用方法")) {
            normalized = normalized + " OR 使用方法";
        }
        if (compact.contains("获取部署落地源") && !normalized.contains("获取部署落地源")) {
            return normalized + " OR 获取部署落地源";
        }
        if (compact.contains("部署落地方式") && !normalized.contains("部署落地方式")) {
            return normalized + " OR 部署落地方式";
        }
        if (compact.contains("部署落地方法") && !normalized.contains("部署落地方法")) {
            return normalized + " OR 部署落地方法";
        }
        if (compact.contains("部署落地指导") && !normalized.contains("部署落地指导")) {
            return normalized + " OR 部署落地指导";
        }
        if (compact.contains("实际操作方法") && !normalized.contains("实际操作方法")) {
            return normalized + " OR 实际操作方法";
        }
        return normalized;
    }

    private static String conciseGoalAnchor(String goalQuestion) {
        if (goalQuestion == null || goalQuestion.isBlank()) return "";
        var anchor = goalQuestion.replaceAll("\\s+", " ").strip();
        var marker = anchor.indexOf("中的");
        if (marker > 0) anchor = anchor.substring(0, marker).strip();
        anchor = anchor.replaceAll("^[“\"']|[”\"'。？！?!]$", "").strip();
        if (anchor.length() < 2 || anchor.length() > 24 || GENERIC_UNITS.contains(anchor)
                || anchor.endsWith("能力定位") || anchor.endsWith("核心定位")
                || anchor.contains("，") || anchor.contains(",") || anchor.contains("；")
                || anchor.contains(";") || anchor.contains("如何") || anchor.contains("哪些")) {
            return "";
        }
        return anchor;
    }

    static String canonicalGoalV8(String goal) {
        if (goal == null || goal.isBlank()) return "用户问题";
        return goal.strip()
                .replace("部署落地方式能力定位", "安装方式介绍")
                .replace("部署落地与可调选项设置", "安装与配置")
                .replace("部署落地可调选项设置", "安装配置")
                .replace("获取部署落地源", "获取安装源和软件源")
                .replace("部署落地源", "安装源和软件源")
                .replace("部署落地方式", "安装方式与部署方式")
                .replace("部署落地指导", "安装指导与部署指导")
                .replace("实际操作光盘引导部署落地", "使用光盘安装")
                .replace("实际操作JDK编译", "使用JDK编译")
                .replace("实际操作方法", "使用方法和操作方法")
                .replace("隔离运行单元镜像", "容器镜像")
                .replace("隔离运行单元资源日常治理", "容器资源管理")
                .replace("日常治理虚拟机", "管理虚拟机")
                .replace("日常治理系统资源", "管理系统资源")
                .replace("日常治理设备", "管理设备")
                .replace("CRI调用边界", "CRI接口调用边界")
                .replace("异常根因定位模块", "故障诊断模块")
                .replace("可调选项设置", "配置")
                .replace("日常治理", "管理")
                .replace("隔离运行单元", "容器")
                .replace("实际操作", "使用")
                .replace("部署落地", "安装部署")
                .replace("异常根因定位", "故障诊断")
                .replace("能力定位", "功能与作用")
                .replace("核心定位", "功能与作用");
    }

    private static boolean containsAlternative(String query, String expected) {
        var normalizedExpected = expected.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        var alternatives = new java.util.LinkedHashSet<String>();
        for (var alternative : query.split("(?i)\\s+OR\\s+")) {
            var normalizedAlternative = alternative.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            if (normalizedAlternative.equals(normalizedExpected)) return true;
            alternatives.add(normalizedAlternative);
        }
        var composed = normalizedExpected.split("[和与]", -1);
        if (composed.length > 1) {
            boolean covered = true;
            for (var part : composed) {
                if (part.length() < 2 || !alternatives.contains(part)) {
                    covered = false;
                    break;
                }
            }
            if (covered) return true;
        }
        return false;
    }

    private static String extractTitleAnchor(String goalQuestion) {
        if (goalQuestion == null || goalQuestion.isBlank()) return "";
        var question = goalQuestion.replaceAll("\\s+", " ").strip();
        var marker = question.indexOf("中的");
        if (marker <= 0) return "";
        var anchor = question.substring(0, marker).strip()
                .replaceFirst("^.*[；;：:]", "").strip();
        if (anchor.length() > 40 || anchor.endsWith("能力定位") || anchor.endsWith("核心定位")
                || anchor.contains("企业变更窗口") || anchor.endsWith("场景")) {
            return "";
        }
        return anchor;
    }

    private static String normalize(String query, String goalQuestion, boolean v7) {
        var original = query == null ? "" : query.replaceAll("[\\\"'“”‘’]", " ")
                .replaceAll("\\s+", " ").strip();
        if (original.isEmpty()) throw new IllegalArgumentException("关键词 Query 不能为空");
        if (v7) original = canonicalizeV7(original, goalQuestion);
        original = canonicalizeDailyGovernance(original);
        var retained = new ArrayList<String>();
        int lexicalUnits = 0;
        for (var unit : original.split(" ")) {
            if (unit.equalsIgnoreCase("OR")) {
                if (!retained.isEmpty() && !retained.getLast().equals("OR")) retained.add("OR");
                continue;
            }
            if (GENERIC_UNITS.contains(unit)) continue;
            if (lexicalUnits == 4) break;
            retained.add(unit);
            lexicalUnits++;
        }
        if (!retained.isEmpty() && retained.getLast().equals("OR")) retained.removeLast();
        if (retained.isEmpty()) {
            return original.split(" ")[0];
        }
        return String.join(" ", retained);
    }

    private static String canonicalizeV7(String query, String goalQuestion) {
        var compact = query.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        var context = (query + " " + (goalQuestion == null ? "" : goalQuestion))
                .replaceAll("\\s+", "").toLowerCase(Locale.ROOT);

        if (compact.contains("实际操作方法")) return "使用方法 OR 操作方法";
        if (compact.matches(".*cri调用边界.*")) return "CRI接口 OR CRI";
        if (compact.contains("虚拟机")
                && (compact.contains("可调选项") || compact.contains("选项设置"))) {
            return "虚拟机配置 OR 虚拟机";
        }
        if (compact.contains("异常根因定位")) return "故障诊断模块 OR 故障诊断";
        if (context.contains("部署") || context.contains("安装")) {
            if (compact.contains("可调选项") || compact.contains("选项设置")
                    || compact.contains("参数设置")) {
                return "安装配置 OR 部署配置";
            }
        }
        if (compact.contains("获取部署落地源")) return "获取安装源 OR 安装源";
        if (compact.contains("部署落地源")) return "安装源 OR 软件源";
        if (compact.contains("部署落地方式")) return "安装方式 OR 部署方式";
        if (compact.contains("部署落地方法")) return "安装方法 OR 部署方法";
        if (compact.contains("部署落地指导")) return "安装指导 OR 部署指导";

        var canonical = query
                .replace("隔离运行单元镜像", "容器镜像")
                .replace("隔离运行单元", "容器")
                .replace("实际操作文档", "使用文档")
                .replace("部署落地", "部署");
        if (canonical.strip().equals("部署") && compact.equals("部署落地")) return query;
        if (canonical.startsWith("实际操作") && canonical.length() > "实际操作".length()) {
            canonical = canonical.substring("实际操作".length());
        }
        return canonical;
    }

    private static String canonicalizeDailyGovernance(String query) {
        if (query.matches(".*(^|\\s)OR($|\\s).*") || !query.contains("日常治理")) return query;
        var units = query.split(" ");
        for (int index = 0; index < units.length; index++) {
            var unit = units[index];
            if (!unit.startsWith("日常治理") || unit.length() == "日常治理".length()) continue;
            var entity = unit.substring("日常治理".length());
            units[index] = "管理" + entity + " OR " + entity;
        }
        return String.join(" ", units);
    }

}
