package com.androidbus.core;

import android.app.Application;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.SparseArray;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Bus {

    private static final String TAG = "[BUS]";
    private static final boolean DEBUG = false;

    @NonNull
    private static final String EVENT_FOR_SERVICE = "_event_for_service";
    private static final int NOT_FIND_ID = -1;

    @NonNull
    private final ExecutorService mExecutorService = Executors.newCachedThreadPool();
    @NonNull
    private final BusRequestReceiver[] mRequestList;
    @NonNull
    private List<ResultHandler> mResultHandlers = new ArrayList<>();
    @NonNull
    private final HashMap<BusResultReceiver, Handler> mHandlerMap = new HashMap<>();
    @NonNull
    private final Class<? extends BusResultService>[] mServices;
    private static volatile Bus sInstance = null;
    @NonNull
    private Context mContext;

    private int mSequenceId = 0;
    @NonNull
    private final SparseArray<BusEvent> mBusEventTable = new SparseArray<>();

    private static final int DEFAULT_EVENT_TYPE = -1;

    // ---------- ------------------- ----------
    // ---------- EVENT TAKER REQUEST ----------
    // ---------- ------------------- ----------

    @Target({ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface EventTakerRequest {
        int value() default DEFAULT_EVENT_TYPE;
    }

    // ---------- ------------------ ----------
    // ---------- EVENT TAKER RESULT ----------
    // ---------- ------------------ ----------

    @Target({ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface EventTakerResult {
        int[] value() default DEFAULT_EVENT_TYPE;
    }

    // ---------- ------------------- ----------
    // ---------- BUS RESULT RECEIVER ----------
    // ---------- ------------------- ----------

    public interface BusResultReceiver {
    }

    // ---------- -------------------- ----------
    // ---------- BUS REQUEST RECEIVER ----------
    // ---------- -------------------- ----------

    public interface BusRequestReceiver extends BusResultReceiver {
    }

    // ---------- --------- ----------
    // ---------- BUS EVENT ----------
    // ---------- --------- ----------

    public final static class BusEvent implements Parcelable, Cloneable {

        private int type;
        @NonNull
        public final Bundle bundleInput;
        @NonNull
        public final Bundle bundleOutput;

        private BusEvent(final int eventType, @Nullable final Bundle bundleInput, @Nullable final Bundle bundleOutput) {
            this.type = eventType;
            this.bundleInput = bundleInput == null ? new Bundle() : new Bundle(bundleInput);
            this.bundleOutput = bundleOutput == null ? new Bundle() : new Bundle(bundleOutput);
        }

        public int getType() {
            return type;
        }

        // ---------- PARCELABLE PART ----------

        @Override
        public void writeToParcel(@NonNull final Parcel parcel, final int ii) {
            parcel.writeInt(type);
            parcel.writeBundle(bundleInput);
            parcel.writeBundle(bundleOutput);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        final static public Creator<BusEvent> CREATOR = new Creator<BusEvent>() {
            @Override
            public BusEvent createFromParcel(@NonNull final Parcel parcel) {
                return new BusEvent(parcel.readInt(), parcel.readBundle(), parcel.readBundle());
            }

            @Override
            public BusEvent[] newArray(final int count) {
                return new BusEvent[count];
            }
        };
    }

    // ---------- --------------- ----------
    // ---------- BUS APPLICATION ----------
    // ---------- --------------- ----------

    public static abstract class BusApplication extends Application {

        private static final String TAG_ERROR_INIT_SERVICES = "error init android bus";

        public abstract List<BusRequestReceiver> createListInboxLayers();

        @SuppressWarnings("unchecked")
        @Override
        public void onCreate() {
            super.onCreate();
            Class<? extends BusResultService>[] services;
            try {
                PackageManager manager = getPackageManager();
                assert manager != null;
                PackageInfo packageInfo = manager.getPackageInfo(getPackageName(), PackageManager.GET_SERVICES);
                ArrayList<Class> busResultReceivers = new ArrayList<>();
                if (packageInfo.services != null) {
                    for (ServiceInfo serviceInfo : packageInfo.services) {
                        Class clazz = Class.forName(serviceInfo.name);
                        if (BusResultService.class.isAssignableFrom(clazz)) {
                            busResultReceivers.add(clazz);
                        }
                    }
                }
                services = busResultReceivers.toArray(new Class[busResultReceivers.size()]);
            } catch (PackageManager.NameNotFoundException | ClassNotFoundException e) {
                services = new Class[0];
                Log.e(TAG_ERROR_INIT_SERVICES, TAG_ERROR_INIT_SERVICES, e);
            }
            Bus.initInstance(this, createListInboxLayers(), services);
        }
    }

    // ---------- ------------------ ----------
    // ---------- BUS RESULT SERVICE ----------
    // ---------- ------------------ ----------

    public static abstract class BusResultService extends Service implements BusRequestReceiver {

        @Override
        public int onStartCommand(@Nullable final Intent intent, final int flags, final int startId) {
            BusEvent event;
            if (intent == null || (event = intent.getParcelableExtra(Bus.EVENT_FOR_SERVICE)) == null) {
                return super.onStartCommand(intent, flags, startId);
            } else {
                BusAnnotationProcessor.processEventAnnotationRequest(event, BusResultService.this);
                return isAutoRestart() ? START_STICKY : START_NOT_STICKY;
            }
        }

        protected boolean isAutoRestart() {
            return false;
        }
    }

    // ---------- ---------------------- ----------
    // ---------- EVENTS FINDER LISTENER ----------
    // ---------- ---------------------- ----------

    public interface EventsFinderListener {
        public void notifyHas(final boolean b);
    }

    // ---------- ------------- ----------
    // ---------- EVENTS FINDER ----------
    // ---------- ------------- ----------

    public final static class EventsFinder {
        final int type;
        @NonNull
        final EventsFinderListener listener;

        public EventsFinder(final int type, @NonNull final EventsFinderListener listener) {
            this.type = type;
            this.listener = listener;
        }

        private boolean has(BusEvent event) {
            return event != null && event.getType() == type;
        }

        @Override
        public String toString() {
            return "{" + type + "}";
        }
    }

    // ---------- CURRENT PROCESSES TABLE ----------

    @NonNull
    private final SparseArray<Vector<EventsFinder>> mEventsFinders = new SparseArray<Vector<EventsFinder>>() {
        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder("finders:[");
            for (int i = size() - 1; i >= 0; i--) {
                builder.append(keyAt(i)).append(":[");
                for (EventsFinder finder : get(keyAt(i))) {
                    builder.append(finder);
                }
                builder.append(i == 0 ? "]" : "],");
            }
            return builder.append("]").toString();
        }
    };

    private void processNewFinder(@NonNull final EventsFinder eventsFinder) {
        synchronized (mBusEventTable) {
            for (int i = mBusEventTable.size(); i >= 0; i--) {
                if (eventsFinder.has(mBusEventTable.get(mBusEventTable.keyAt(i)))) {
                    eventsFinder.listener.notifyHas(true);
                    return;
                }
            }
            eventsFinder.listener.notifyHas(false);
        }
    }

    private void processNewEvent(@NonNull final BusEvent event) {
        for (EventsFinder finder : mEventsFinders.get(event.getType())) {
            if (finder.has(event)) {
                finder.listener.notifyHas(true);
                return;
            }
        }
    }

    private static int addEventToTable(BusEvent event) {
        synchronized (sInstance.mBusEventTable) {
            final int id = sInstance.mSequenceId++;
            sInstance.mBusEventTable.put(id, event);
            sInstance.processNewEvent(event);
            if (DEBUG) {
                Log.e(TAG, TAG + " add event " + id + " size=" + sInstance.mBusEventTable.size());
            }
            return id;
        }
    }

    private static void removeEventWithId(final int id) {
        if (id < 0) {
            return;
        }
        synchronized (sInstance.mBusEventTable) {
            sInstance.mBusEventTable.remove(id);
            if (DEBUG) {
                Log.e(TAG, TAG + " remove event " + id + " size=" + sInstance.mBusEventTable.size());
            }
        }
    }

    // ---------- CURRENT PROCESSES TABLE END ----------

    private static void initInstance(@NonNull final Context context,
                                     @NonNull final List<BusRequestReceiver> listInbox,
                                     @NonNull final Class<? extends BusResultService>[] services) {
        sInstance = new Bus(context, listInbox, services);
    }

    private Bus(@NonNull final Context context,
                @NonNull final List<BusRequestReceiver> listInbox,
                @NonNull final Class<? extends BusResultService>[] services) {
        mContext = context;
        mServices = services;

        mRequestList = new BusRequestReceiver[listInbox.size()];
        for (int i = 0; i < mRequestList.length; i++) {
            BusAnnotationProcessor.processCacheRequest(mRequestList[i] = listInbox.get(i));
        }
        for (Class clazz : mServices) {
            BusAnnotationProcessor.processCacheRequestClass(clazz);
        }
    }

    // ----- PUBLIC METHODS START -----

    public static void addFinder(@NonNull final EventsFinder eventsFinder) {
        synchronized (sInstance.mEventsFinders) {
            Vector<EventsFinder> eventsFinders = sInstance.mEventsFinders.get(eventsFinder.type);
            if (eventsFinders == null) {
                eventsFinders = new Vector<>();
                sInstance.mEventsFinders.put(eventsFinder.type, eventsFinders);
            }
            eventsFinders.add(eventsFinder);
            sInstance.processNewFinder(eventsFinder);
            if (DEBUG) {
                Log.e(TAG, TAG + " add finder " + eventsFinder.type + " filters=" + sInstance.mEventsFinders);
            }
        }
    }

    public static void removeFinder(@NonNull final EventsFinder eventsFinder) {
        synchronized (sInstance.mEventsFinders) {
            Vector<EventsFinder> eventsFinders = sInstance.mEventsFinders.get(eventsFinder.type);
            if (eventsFinders != null) {
                eventsFinders.remove(eventsFinder);
            }
            if (eventsFinders != null && eventsFinders.size() == 0) {
                sInstance.mEventsFinders.remove(eventsFinder.type);
            }
            if (DEBUG) {
                Log.e(TAG, TAG + " remove finder " + eventsFinder.type + " filters=" + sInstance.mEventsFinders);
            }
        }
    }

    @NonNull
    public static Context getContext() {
        return sInstance.mContext;
    }

    public synchronized static void subscribe(@NonNull final BusResultReceiver receiver) {
        if (sInstance.mHandlerMap.containsKey(receiver)) {
            return;
        }
        BusAnnotationProcessor.processCacheResult(receiver);
        ResultHandler handler = new ResultHandler(receiver);
        sInstance.mHandlerMap.put(receiver, handler);
        ArrayList<ResultHandler> listBuf = new ArrayList<>(sInstance.mResultHandlers);
        listBuf.add(handler);
        sInstance.mResultHandlers = listBuf;
    }

    public synchronized static void unSubscribe(@NonNull final BusResultReceiver receiver) {
        Bus bus = sInstance;
        ArrayList<ResultHandler> listBuf = new ArrayList<>(bus.mResultHandlers);
        ResultHandler resultHandler = (ResultHandler) bus.mHandlerMap.get(receiver);
        listBuf.remove(resultHandler);
        bus.mHandlerMap.remove(receiver);
        bus.mResultHandlers = listBuf;
    }

    public static void sendResult(final int eventType) {
        sendResult(eventType, null, null);
    }

    public static void sendResult(final int eventType,
                                  @Nullable final Bundle bundleInput,
                                  @Nullable final Bundle bundleOutput) {
        BusEvent event = new BusEvent(eventType, bundleInput, bundleOutput);
        Bus bus = sInstance;
        for (ResultHandler handler : bus.mResultHandlers) {
            if (BusAnnotationProcessor.canProcessEventResult(event, handler.receiver)) {
                Bus.sendEventToTarget(handler, event);
            }
        }
    }

    public static void sendRequest(final int eventType) {
        sendRequest(eventType, null, null, false);
    }

    public static void sendRequest(final int eventType,
                                   @Nullable final Bundle bundleInput,
                                   @Nullable final Bundle bundleOutput) {
        sendRequest(eventType, bundleInput, bundleOutput, false);
    }

    public static void sendRequest(final int eventType,
                                   @Nullable final Bundle bundleInput,
                                   @Nullable final Bundle bundleOutput,
                                   final boolean canFind) {
        final BusEvent event = new BusEvent(eventType, bundleInput, bundleOutput);
        Bus bus = sInstance;
        for (BusRequestReceiver requestReceiver : bus.mRequestList) {
            if (BusAnnotationProcessor.canProcessEventRequest(event, requestReceiver)) {
                final BusRequestReceiver requestReceiverParam = requestReceiver;
                final int id = canFind ? addEventToTable(event) : NOT_FIND_ID;
                sInstance.mExecutorService.execute(new Runnable() {
                    @Override
                    public void run() {
                        int priorityMain = Looper.getMainLooper().getThread().getPriority();
                        if (priorityMain <= Thread.currentThread().getPriority()) {
                            int priorityThis = priorityMain > Thread.MIN_PRIORITY ? priorityMain - 1 : priorityMain;
                            Thread.currentThread().setPriority(priorityThis);
                        }
                        BusAnnotationProcessor.processEventAnnotationRequest(event, requestReceiverParam);
                        removeEventWithId(id);
                    }
                });
            }
        }
        for (Class<? extends BusResultService> clazz : bus.mServices) {
            if (BusAnnotationProcessor.canProcessEventRequestClass(event, clazz)) {
                bus.mContext.startService(bus.createIntent(event, clazz));
            }
        }
    }

    // ---------- PUBLIC METHODS FINISH ----------

    private static void sendEventToTarget(@NonNull final Handler handler, @NonNull final BusEvent event) {
        Message message = Message.obtain(handler, 0, 0, 0, event);
        assert message != null;
        message.sendToTarget();
    }

    @NonNull
    private Intent createIntent(@NonNull final BusEvent event, @NonNull final Class<? extends BusResultService> clazz) {
        return new Intent(mContext, clazz).putExtra(EVENT_FOR_SERVICE, event);
    }

    private final static class ResultCallback implements Handler.Callback {

        @NonNull
        public final BusResultReceiver receiver;

        private ResultCallback(@NonNull final BusResultReceiver receiver) {
            this.receiver = receiver;
        }

        @Override
        public boolean handleMessage(@NonNull final Message msg) {
            return msg.obj != null && BusAnnotationProcessor.processEventAnnotationResult((BusEvent) msg.obj, receiver);
        }
    }

    private final static class ResultHandler extends Handler {

        @NonNull
        public final BusResultReceiver receiver;

        public ResultHandler(@NonNull final BusResultReceiver receiver) {
            super(new ResultCallback(receiver));
            this.receiver = receiver;
        }
    }

    // --------- BUS ANNOTATION PROCESSOR ----------

    private final static class BusAnnotationProcessor {

        @NonNull
        private static final HashMap<String, Method> requestCache = new HashMap<>();
        @NonNull
        private static final HashMap<String, Method> resultCache = new HashMap<>();
        @NonNull
        private static final HashMap<String, Method> resultReceiveAllEventCache = new HashMap<>();

        @NonNull
        private static String getKey(@NonNull final BusEvent event, @NonNull final Object o) {
            return o.getClass().getCanonicalName() + "_" + event.getType();
        }

        @NonNull
        private static String getKey(final int i, @NonNull final Class o) {
            return o.getCanonicalName() + "_" + i;
        }

        public static boolean canProcessEventRequest(@NonNull final BusEvent event, @NonNull final Object o) {
            return requestCache.get(getKey(event, o)) != null;
        }

        public static boolean canProcessEventRequestClass(@NonNull final BusEvent event, @NonNull final Class o) {
            return requestCache.get(getKey(event.getType(), o)) != null;
        }

        public static boolean canProcessEventResult(@NonNull final BusEvent event, @NonNull final Object o) {
            return resultCache.get(getKey(event, o)) != null || resultReceiveAllEventCache.get(o.getClass().getCanonicalName()) != null;
        }

        public static void processCacheRequest(@NonNull final Object o) {
            processCacheRequestClass(o.getClass());
        }

        public static void processCacheRequestClass(@NonNull final Class clazz) {
            if (requestCache.containsKey(clazz.getCanonicalName())) {
                return;
            }
            Method[] methods = clazz.getMethods();
            synchronized (requestCache) {
                for (Method method : methods) {
                    EventTakerRequest eventTaker = method.getAnnotation(EventTakerRequest.class);
                    if (eventTaker != null) {
                        requestCache.put(getKey(eventTaker.value(), clazz), method);
                    }
                }
                requestCache.put(clazz.getCanonicalName(), null);
            }

        }

        public static void processCacheResult(@NonNull final Object o) {
            if (resultCache.containsKey(o.getClass().getCanonicalName())) {
                return;
            }
            Method[] methods = o.getClass().getMethods();
            synchronized (resultCache) {
                for (Method method : methods) {
                    EventTakerResult eventTaker = method.getAnnotation(EventTakerResult.class);
                    if (eventTaker == null) {
                        continue;
                    }
                    int[] values = eventTaker.value();
                    for (int value : values) {
                        if (value == DEFAULT_EVENT_TYPE) {
                            resultReceiveAllEventCache.put(o.getClass().getCanonicalName(), method);
                        } else {
                            resultCache.put(getKey(value, o.getClass()), method);
                        }
                    }
                }
                resultCache.put(o.getClass().getCanonicalName(), null);
            }
        }

        public static boolean processEventAnnotationRequest(@NonNull final BusEvent event, @NonNull final Object o) {
            Method needMethod = requestCache.get(getKey(event, o));
            return invokeMethod(needMethod, o, event);
        }

        public static boolean processEventAnnotationResult(@NonNull final BusEvent event, @NonNull final Object o) {
            Method defaultValueMethod = resultReceiveAllEventCache.get(o.getClass().getCanonicalName());
            Method needMethod = resultCache.get(getKey(event, o));
            boolean result = invokeMethod(needMethod, o, event);
            result = invokeMethod(defaultValueMethod, o, event) || result;
            return result;
        }

        private static boolean invokeMethod(@Nullable final Method method, @NonNull final Object o, @NonNull final BusEvent event) {
            if (method == null) {
                return false;
            }
            try {
                Class<?>[] classes = method.getParameterTypes();
                if (classes.length == 0) {
                    method.invoke(o);
                    return true;
                } else if (classes.length == 1 && BusEvent.class.equals(classes[0])) {
                    method.invoke(o, event);
                    return true;
                } else {
                    throw new RuntimeException("invalid params in object=" + o.getClass().getName() + " method=" + method.getName());
                }
            } catch (InvocationTargetException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }
}