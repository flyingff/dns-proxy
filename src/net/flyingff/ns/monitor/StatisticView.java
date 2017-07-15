package net.flyingff.ns.monitor;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

public class StatisticView extends JComponent {
    private final Deque<Integer> dataQueue = new ArrayDeque<>();
    private final Color colorData = new Color(0xFF99000);
    @Override
    public void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(Color.white);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.gray);
        g.drawRect(0, 0, w - 1, h - 1);

        g.setColor(colorData);
        synchronized (this) {
            Iterator<Integer> it = dataQueue.iterator();
            for (int i = w - 2; i > 0 && it.hasNext(); i--) {
                int val = it.next() * h / 1024;
                g.drawLine(i, h - 1, i, h - val);
            }
            // remove redundant data
            while (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    public StatisticView() {
        Thread th = new Thread(() -> {
            //noinspection InfiniteLoopStatement
            while(true) {
                try { Thread.sleep(500); } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                synchronized (StatisticView.this) {
                    dataQueue.addFirst(accumulate);
                    accumulate = 0;
                }
                repaint();
            }
        });
        th.setDaemon(true);
        th.start();
    }

    private int accumulate = 0;
    @SuppressWarnings("WeakerAccess")
    public void accumulate(int data) {
        synchronized (this) {
            accumulate += data;
        }
    }
}
