package edu.sustech.cs307.physicalOperator;

final class PlanTreeFormatter {
    private PlanTreeFormatter() {
    }

    static String formatUnaryTree(String nodeHeader, PhysicalOperator child) {
        StringBuilder sb = new StringBuilder(nodeHeader);
        if (child == null) {
            return sb.toString();
        }
        String[] childLines = child.toString().split("\\R");
        if (childLines.length > 0) {
            sb.append("\n\u2514\u2500\u2500 ").append(childLines[0]);
            for (int i = 1; i < childLines.length; i++) {
                sb.append("\n    ").append(childLines[i]);
            }
        }
        return sb.toString();
    }

    static String formatBinaryTree(String nodeHeader, PhysicalOperator left, PhysicalOperator right) {
        StringBuilder sb = new StringBuilder(nodeHeader);
        appendChildTree(sb, left, "\u251c\u2500\u2500 ", "\u2502   ");
        appendChildTree(sb, right, "\u2514\u2500\u2500 ", "    ");
        return sb.toString();
    }

    private static void appendChildTree(StringBuilder sb, PhysicalOperator child, String firstPrefix,
                                        String nextPrefix) {
        if (child == null) {
            return;
        }
        String[] childLines = child.toString().split("\\R");
        if (childLines.length > 0) {
            sb.append("\n").append(firstPrefix).append(childLines[0]);
            for (int i = 1; i < childLines.length; i++) {
                sb.append("\n").append(nextPrefix).append(childLines[i]);
            }
        }
    }
}
