package edu.sustech.cs307.index;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class InMemoryBPlusTreeIndex {
    private static final int DEFAULT_MAX_KEYS = 4;

    private final int maxKeys;
    private Node root;
    private int nextNodeId;

    public InMemoryBPlusTreeIndex() {
        this(DEFAULT_MAX_KEYS);
    }

    public InMemoryBPlusTreeIndex(int maxKeys) {
        this.maxKeys = Math.max(3, maxKeys);
        this.nextNodeId = 0;
        this.root = new LeafNode(nextNodeId++);
    }

    public void insert(Value key, RID rid) throws DBException {
        LeafNode leaf = findLeaf(key);
        int index = lowerBound(leaf.keys, key);
        if (index < leaf.keys.size() && compare(leaf.keys.get(index), key) == 0) {
            leaf.values.get(index).add(new RID(rid));
            return;
        }
        leaf.keys.add(index, key);
        ArrayList<RID> bucket = new ArrayList<>();
        bucket.add(new RID(rid));
        leaf.values.add(index, bucket);
        if (leaf.keys.size() > maxKeys) {
            splitLeaf(leaf);
        }
    }

    public void delete(Value key, RID rid) throws DBException {
        LeafNode leaf = findLeaf(key);
        int index = lowerBound(leaf.keys, key);
        if (index >= leaf.keys.size() || compare(leaf.keys.get(index), key) != 0) {
            return;
        }
        leaf.values.get(index).remove(rid);
        if (leaf.values.get(index).isEmpty()) {
            leaf.keys.remove(index);
            leaf.values.remove(index);
        }
    }

    public List<RID> search(String operator, Value key) throws DBException {
        ArrayList<RID> result = new ArrayList<>();
        switch (operator) {
            case "=" -> collectEqual(key, result);
            case ">" -> collectRange(key, false, null, false, result);
            case ">=" -> collectRange(key, true, null, false, result);
            case "<" -> collectRange(null, false, key, false, result);
            case "<=" -> collectRange(null, false, key, true, result);
            default -> {
                return List.of();
            }
        }
        return result;
    }

    public String printNodes() {
        StringBuilder builder = new StringBuilder();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Node node = queue.remove();
            builder.append(node.describe()).append(System.lineSeparator());
            if (node instanceof InternalNode internalNode) {
                queue.addAll(internalNode.children);
            }
        }
        return builder.toString();
    }

    private void collectEqual(Value key, ArrayList<RID> result) throws DBException {
        LeafNode leaf = findLeaf(key);
        int index = lowerBound(leaf.keys, key);
        if (index < leaf.keys.size() && compare(leaf.keys.get(index), key) == 0) {
            addAllRids(result, leaf.values.get(index));
        }
    }

    private void collectRange(Value low, boolean lowInclusive, Value high, boolean highInclusive,
                              ArrayList<RID> result) throws DBException {
        LeafNode leaf = low == null ? firstLeaf() : findLeaf(low);
        while (leaf != null) {
            for (int i = 0; i < leaf.keys.size(); i++) {
                Value key = leaf.keys.get(i);
                if (low != null) {
                    int lowComparison = compare(key, low);
                    if (lowComparison < 0 || (lowComparison == 0 && !lowInclusive)) {
                        continue;
                    }
                }
                if (high != null) {
                    int highComparison = compare(key, high);
                    if (highComparison > 0 || (highComparison == 0 && !highInclusive)) {
                        return;
                    }
                }
                addAllRids(result, leaf.values.get(i));
            }
            leaf = leaf.next;
        }
    }

    private LeafNode firstLeaf() {
        Node node = root;
        while (node instanceof InternalNode internalNode) {
            node = internalNode.children.get(0);
        }
        return (LeafNode) node;
    }

    private LeafNode findLeaf(Value key) throws DBException {
        Node node = root;
        while (node instanceof InternalNode internalNode) {
            int childIndex = upperBound(internalNode.keys, key);
            node = internalNode.children.get(childIndex);
        }
        return (LeafNode) node;
    }

    private void splitLeaf(LeafNode leaf) throws DBException {
        LeafNode right = new LeafNode(nextNodeId++);
        int split = (leaf.keys.size() + 1) / 2;
        moveTail(leaf.keys, right.keys, split);
        moveTail(leaf.values, right.values, split);
        right.next = leaf.next;
        leaf.next = right;
        right.parent = leaf.parent;
        insertIntoParent(leaf, right.keys.get(0), right);
    }

    private void insertIntoParent(Node left, Value key, Node right) throws DBException {
        if (left.parent == null) {
            InternalNode newRoot = new InternalNode(nextNodeId++);
            newRoot.keys.add(key);
            newRoot.children.add(left);
            newRoot.children.add(right);
            left.parent = newRoot;
            right.parent = newRoot;
            root = newRoot;
            return;
        }
        InternalNode parent = left.parent;
        int leftIndex = parent.children.indexOf(left);
        parent.keys.add(leftIndex, key);
        parent.children.add(leftIndex + 1, right);
        right.parent = parent;
        if (parent.keys.size() > maxKeys) {
            splitInternal(parent);
        }
    }

    private void splitInternal(InternalNode node) throws DBException {
        InternalNode right = new InternalNode(nextNodeId++);
        int promoteIndex = node.keys.size() / 2;
        Value promoteKey = node.keys.get(promoteIndex);

        right.keys.addAll(node.keys.subList(promoteIndex + 1, node.keys.size()));
        right.children.addAll(node.children.subList(promoteIndex + 1, node.children.size()));
        for (Node child : right.children) {
            child.parent = right;
        }

        node.keys.subList(promoteIndex, node.keys.size()).clear();
        node.children.subList(promoteIndex + 1, node.children.size()).clear();
        right.parent = node.parent;
        insertIntoParent(node, promoteKey, right);
    }

    private int lowerBound(List<Value> keys, Value key) throws DBException {
        int low = 0;
        int high = keys.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (compare(keys.get(mid), key) < 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private int upperBound(List<Value> keys, Value key) throws DBException {
        int low = 0;
        int high = keys.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (compare(keys.get(mid), key) <= 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private int compare(Value left, Value right) throws DBException {
        return ValueComparer.compare(left, right);
    }

    private void addAllRids(ArrayList<RID> target, List<RID> source) {
        for (RID rid : source) {
            target.add(new RID(rid));
        }
    }

    private <T> void moveTail(List<T> source, List<T> target, int fromIndex) {
        target.addAll(source.subList(fromIndex, source.size()));
        source.subList(fromIndex, source.size()).clear();
    }

    private abstract static class Node {
        protected final int id;
        protected final ArrayList<Value> keys;
        protected InternalNode parent;

        private Node(int id) {
            this.id = id;
            this.keys = new ArrayList<>();
        }

        protected String keyString() {
            return keys.stream().map(Objects::toString).toList().toString();
        }

        protected abstract String describe();
    }

    private static class InternalNode extends Node {
        private final ArrayList<Node> children;

        private InternalNode(int id) {
            super(id);
            this.children = new ArrayList<>();
        }

        @Override
        protected String describe() {
            return "InternalNode{id=" + id + ", keys=" + keyString()
                    + ", children=" + children.stream().map(child -> child.id).toList() + "}";
        }
    }

    private static class LeafNode extends Node {
        private final ArrayList<ArrayList<RID>> values;
        private LeafNode next;

        private LeafNode(int id) {
            super(id);
            this.values = new ArrayList<>();
        }

        @Override
        protected String describe() {
            ArrayList<Integer> bucketSizes = new ArrayList<>();
            for (List<RID> bucket : values) {
                bucketSizes.add(bucket.size());
            }
            return "LeafNode{id=" + id + ", keys=" + keyString()
                    + ", ridCounts=" + bucketSizes
                    + ", next=" + (next == null ? "null" : next.id) + "}";
        }
    }
}
