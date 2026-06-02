package edu.sustech.cs307.logicalOperator;

import java.util.List;

public abstract class LogicalOperator {
    protected List<LogicalOperator> childern;

    public LogicalOperator(List<LogicalOperator> children) {
        this.childern = children;
    }

    public List<LogicalOperator> getChildren() {
        return childern;
    }

    public LogicalOperator getChild() {
        if (childern != null && !childern.isEmpty()) {
            return childern.get(0);
        }
        return null;
    }

    protected String formatUnaryTree(String nodeHeader, LogicalOperator child) {
        StringBuilder sb = new StringBuilder(nodeHeader);
        if (child == null) {
            return sb.toString();
        }
        String[] childLines = child.toString().split("\\R");
        if (childLines.length > 0) {
            sb.append("\n└── ").append(childLines[0]);
            for (int i = 1; i < childLines.length; i++) {
                sb.append("\n    ").append(childLines[i]);
            }
        }
        return sb.toString();
    }

    protected String formatBinaryTree(String nodeHeader, LogicalOperator left, LogicalOperator right) {
        StringBuilder sb = new StringBuilder(nodeHeader);
        appendChildTree(sb, left, "├── ", "│   ");
        appendChildTree(sb, right, "└── ", "    ");
        return sb.toString();
    }

    private void appendChildTree(StringBuilder sb, LogicalOperator child, String firstPrefix, String nextPrefix) {
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

    public abstract String toString();
}
