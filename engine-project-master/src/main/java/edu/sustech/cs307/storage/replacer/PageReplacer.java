package edu.sustech.cs307.storage.replacer;

public interface PageReplacer {
    int Victim();

    void Pin(int frameId);//内存中某个格子的编号

    void Unpin(int frameId);

    int size();

    void Clear();
}
