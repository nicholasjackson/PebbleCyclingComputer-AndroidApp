package com.njackson.virtualpebble;

import android.content.Context;
import com.getpebble.android.kit.util.PebbleDictionary;

/**
 * Created by server on 18/03/2014.
 */
public interface IMessageManager extends Runnable {

    public boolean offer(final PebbleDictionary data);
    public boolean offerIfLow(final PebbleDictionary data, int sizeMax);

    public void showWatchFace();
    public void hideWatchFace();

    public void showSimpleNotificationOnWatch(String title, String text);
}
