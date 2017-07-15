package net.flyingff.ns;

public interface IMonitor<T> {
    void setProgress(int val);
    void setProgress2(int val);
    void setLabel(String text);
    void addItem(T item);
    void removeItem(T item);
    void refresh();
    void addCurrency(int bits);
}
