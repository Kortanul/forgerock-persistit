/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.persistit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import javax.management.MBeanNotificationInfo;
import javax.management.Notification;
import javax.management.NotificationBroadcasterSupport;
import javax.management.ObjectName;

import com.persistit.logging.LogBase;
import com.persistit.logging.PersistitLogMessage.LogItem;
import com.persistit.mxbeans.AlertMonitorMXBean;
import com.persistit.util.Util;

/**
 * Manage the process of accumulating and logging abnormal events such as
 * IOExceptions and measurements outside of expected thresholds. Concrete
 * AbstractAlertMonitor implementations are set up and registered as MXBeans
 * during Persistit initialization, and their behavior can be modified through
 * the MXBean interface.
 * 
 * @author peter
 * 
 */
public final class AlertMonitor extends NotificationBroadcasterSupport implements AlertMonitorMXBean {

    private final static int DEFAULT_HISTORY_LENGTH = 10;
    private final static int MINIMUM_HISTORY_LENGTH = 1;
    private final static int MAXIMUM_HISTORY_LENGTH = 1000;

    private final static long DEFAULT_WARN_INTERVAL = 600000;
    private final static long MINIMUM_WARN_INTERVAL = 1000;
    private final static long MAXIMUM_WARN_INTERVAL = 86400000;

    private final static long DEFAULT_ERROR_INTERVAL = 15000;
    private final static long MINIMUM_ERROR_INTERVAL = 1000;
    private final static long MAXIMUM_ERROR_INTERVAL = 86400000;

    /**
     * Severity of an event
     */
    public enum AlertLevel {
        /**
         * Normal state
         */
        NORMAL,
        /**
         * Warning - system is running but could be experiencing trouble that
         * could lead to an error. Example: too many journal files, disk is
         * filling up, pruning is falling behind etc.
         */
        WARN,
        /**
         * Error - system is generating errors and failing. Example: disk is
         * full.
         */
        ERROR,
    }

    protected final static String EVENT_FORMAT = "event %,5d: %s";
    protected final static String AGGREGATION_FORMAT = "Minimum=%,d Maximum=%,d Total=%,d";
    protected final static String EXTRA_FORMAT = "Extra=%s";

    public class History {
        private AlertLevel _level = AlertLevel.NORMAL;
        private List<Event> _eventList = new ArrayList<Event>();
        private volatile long _firstEventTime = Long.MAX_VALUE;
        private volatile long _lastWarnLogTime = Long.MIN_VALUE;
        private volatile long _lastErrorLogTime = Long.MIN_VALUE;
        private volatile int _reportedCount;
        private volatile Event _firstEvent;
        private volatile int _count;

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            synchronized (AlertMonitor.this) {
                final Event event = getLastEvent();
                if (_count > 0) {
                    sb.append(String.format(EVENT_FORMAT, _count, event == null ? "missing" : format(event)));
                }
            }
            return sb.toString();
        }

        public String getDetailedHistory() {
            final StringBuilder sb = new StringBuilder();
            synchronized (AlertMonitor.this) {
                int size = _eventList.size();
                if (_count > 0) {
                    sb.append(String.format(EVENT_FORMAT, 1, format(_firstEvent)));
                    for (int index = _count > size ? 0 : 1; index < size; index++) {
                        if (sb.length() > 0) {
                            sb.append(Util.NEW_LINE);
                        }
                        sb.append(String.format(EVENT_FORMAT, _count - size + index + 1, format(_eventList.get(index))));
                    }
                }
            }
            return sb.toString();
        }

        /**
         * @return the current alert level of this monitor
         */
        public AlertLevel getLevel() {
            return _level;
        }

        /**
         * @return time of the first event
         */
        public long getFirstEventTime() {
            return _firstEventTime;
        }

        /**
         * @return time of the last event
         */
        public long getLastEventTime() {
            synchronized (AlertMonitor.this) {
                if (_eventList.size() > 0) {
                    return _eventList.get(_eventList.size() - 1)._time;
                } else {
                    return Long.MIN_VALUE;
                }
            }
        }

        /**
         * @return interval between first and last recorded event, in seconds
         */
        public long getDuration() {
            final long latest = getLastEventTime();
            if (latest == Long.MIN_VALUE) {
                return 0;
            }
            return (latest - _firstEventTime) / 1000;
        }

        /**
         * @return the recent history
         */
        public List<Event> getEventList() {
            synchronized (AlertMonitor.this) {
                return new ArrayList<Event>(_eventList);
            }
        }

        /**
         * @return The first event added to this history, or <code>null</code>
         *         if there have been no events.
         */
        public Event getFirstEvent() {
            return _firstEvent;
        }

        /**
         * @return The last event added to this history, or <code>null</code> if
         *         there have been no events.
         */
        public Event getLastEvent() {
            synchronized (AlertMonitor.this) {
                if (_eventList.isEmpty()) {
                    return null;
                } else {
                    return _eventList.get(_eventList.size() - 1);
                }
            }
        }

        /**
         * @return the count of events posted to this history
         */
        public int getCount() {
            return _count;
        }

        /**
         * Emit a log message to signify an ongoing condition. This method is
         * called periodically. It keeps track of when the last message was
         * added to the log and writes a new recurring message only as
         * frequently as allowed by
         * {@link AlertMonitor#getErrorLogTimeInterval()} or
         * {@link AlertMonitor#getWarnLogTimeInterval()}.
         * 
         * @param now
         *            current system time as returned by
         *            {@link System#currentTimeMillis()}
         * @param force
         *            if <code>true</code> and the level is WARN or ERROR, this
         *            method emits a notification regardless of whether the
         *            notification interval has elapsed; otherwise this method
         *            does nothing unless the interval has elapsed.
         */
        public void poll(final long now, final boolean force) {
            int count = getCount();
            if (count > _reportedCount) {
                switch (_level) {
                case ERROR:
                    if (force || now > _lastErrorLogTime + _errorLogTimeInterval) {
                        _lastErrorLogTime = now;
                        log(this);
                        sendNotification(this);
                        _reportedCount = count;
                    }
                    break;
                case WARN:
                    if (force || now > _lastWarnLogTime + _warnLogTimeInterval) {
                        _lastWarnLogTime = now;
                        log(this);
                        sendNotification(this);
                        _reportedCount = count;
                    }
                    break;

                default:
                    // Ignore the NORMAL case
                }
            }
        }

        /**
         * Add an Event at the specified level
         * 
         * @param event
         *            the <code>Event</code> to add
         * @param level
         *            the <code>AlertLevel</code> that should be assigned to it
         */
        private void addEvent(final Event event, final AlertLevel level) {
            trim(_historyLength - 1);
            _eventList.add(event);
            _count++;
            if (event.getTime() < _firstEventTime) {
                _firstEventTime = event.getTime();
                _firstEvent = event;
            }
            event.added(this);
            _level = level;
        }

        /**
         * Remove events until the event list is no larger than the supplied
         * size
         * 
         * @param size
         *            maximum number of events to retain on the event list
         */
        private void trim(final int size) {
            while (_eventList.size() > size) {
                _eventList.get(0).removed(this);
                _eventList.remove(0);
            }
        }
    }

    /**
     * Holder for event data including the event arguments and the time the
     * event was posted.
     * 
     */
    public static class Event {
        private final LogItem _logItem;
        private final Object[] _args;
        private final long _time;

        /**
         * Construct an <code>Event</code> for the specified {@link LogItem} and
         * arguments with the current system time.
         * 
         * @param logItem
         *            <code>LogItem</code> to be used in logging this event
         * @param args
         *            arguments specific to the <code>LogItem</code>
         */
        public Event(LogItem logItem, Object... args) {
            this(System.currentTimeMillis(), logItem, args);
        }

        /**
         * Construct an <code>Event</code> for the specified {@link LogItem} and
         * arguments with the specified system time.
         * 
         * @param time
         *            System time in milliseconds at which the event occurred
         * @param logItem
         *            <code>LogItem</code> to be used in logging this event
         * @param args
         *            arguments specific to the <code>LogItem</code>
         */
        public Event(long time, LogItem logItem, Object... args) {
            _logItem = logItem;
            _args = args;
            _time = time;
        }

        /**
         * @return The {@link LogItem} assigned to the event
         */
        public LogItem getLogItem() {
            return _logItem;
        }

        /**
         * 
         * @return the system time at which the event occurred
         */
        public long getTime() {
            return _time;
        }

        /**
         * 
         * @return arguments supplied with the event and used to format a log or
         *         notification message to describe this event
         */
        public Object[] getArgs() {
            return _args;
        }

        /**
         * 
         * @return the first element of the argument array, or
         *         <code>null<code> if the array is empty
         */
        public Object getFirstArg() {
            return _args.length > 0 ? _args[0] : null;
        }

        /**
         * Called when this <code>Event</code> is added to a
         * <code>History</code>. By default this method does nothing, but an
         * Event subclass can use this method to compute statistics or perform
         * other custom operations. This method is called after the event has
         * been added.
         * 
         * @param history
         *            the <code>History</code> to which this event has been
         *            added
         */
        protected void added(final History history) {
            // Default: do nothing
        }

        /**
         * Called when this <code>Event</code> is removed from a
         * <code>History</code>. By default this method does nothing, but an
         * Event subclass can use this method to compute statistics or perform
         * other custom operations. This method is called before the event has
         * been removed.
         * 
         * @param history
         *            the <code>History</code> from which this event will be
         *            removed
         */
        protected void removed(final History history) {
            // Default: do nothing
        }

        /**
         * @return a description of the event with human-readable time and log
         *         message
         */
        @Override
        public String toString() {
            return Util.date(_time) + " " + _logItem.logMessage(_args);
        }
    }

    final static String NOTIFICATION_TYPE = "com.persistit.AlertMonitor";

    private final Map<String, History> _historyMap = new TreeMap<String, History>();

    private volatile long _warnLogTimeInterval = DEFAULT_WARN_INTERVAL;
    private volatile long _errorLogTimeInterval = DEFAULT_ERROR_INTERVAL;
    private volatile int _historyLength = DEFAULT_HISTORY_LENGTH;

    private AtomicLong _notificationSequence = new AtomicLong();
    private volatile ObjectName _objectName;

    /**
     * Constructor
     */
    AlertMonitor() {
        super(Executors.newCachedThreadPool());
    }

    /**
     * Record the <code>ObjectName</code> with which this instance was
     * registered as an MBean
     * 
     * @param on
     *            The <code>ObjectName</code>
     */
    void setObjectName(final ObjectName on) {
        _objectName = on;
    }

    /**
     * @return the <code>ObjectName</code> with which this instance was
     *         registered as an MBean
     */
    ObjectName getObjectName() {
        return _objectName;
    }

    /**
     * Post an event. If necessary, create a {@link History} for the specified
     * category and then add the event to it. Subsequently, poll the
     * <code>History</code> to cause any pending notifications or log messages
     * to be emitted. 
     * 
     * @param event
     *            A Event object describing what happened
     * @param category
     *            A String describing the nature of the event. A separate
     *            event-history is maintained for each unique category.
     * @param level
     *            Indicates whether this event is a warning or an error.
     */
    public synchronized final void post(Event event, final String category, AlertLevel level) {
        History history = _historyMap.get(category);
        if (history == null) {
            history = new History();
            _historyMap.put(category, history);
        }
        history.addEvent(event, level);
        history.poll(event.getTime(), false);
    }

    /**
     * Restore this alert monitor to level {@link AlertLevel#NORMAL} with and
     * remove all histories for all categories. The logging time intervals and
     * history length are not changed.
     */
    @Override
    public synchronized void reset() {
        _historyMap.clear();
    }

    /**
     * @return the interval in milliseconds between successive log entries for
     *         this monitor when its {@link AlertLevel#WARN}.
     */
    @Override
    public long getWarnLogTimeInterval() {
        return _warnLogTimeInterval;
    }

    /**
     * Set the interval between successive log entries for this monitor when its
     * {@link AlertLevel#WARN}.
     * 
     * @param warnLogTimeInterval
     *            the interval in milliseconds
     */
    @Override
    public void setWarnLogTimeInterval(long warnLogTimeInterval) {
        Util.rangeCheck(warnLogTimeInterval, MINIMUM_WARN_INTERVAL, MAXIMUM_WARN_INTERVAL);
        _warnLogTimeInterval = warnLogTimeInterval;
    }

    /**
     * @return the interval in milliseconds between successive log entries for
     *         this monitor when its {@link AlertLevel#ERROR}.
     */
    @Override
    public long getErrorLogTimeInterval() {
        return _errorLogTimeInterval;
    }

    /**
     * Set the interval between successive log entries for this monitor when its
     * {@link AlertLevel#ERROR}.
     * 
     * @param errorLogTimeInterval
     *            the interval in milliseconds
     */
    @Override
    public void setErrorLogTimeInterval(long errorLogTimeInterval) {
        Util.rangeCheck(errorLogTimeInterval, MINIMUM_ERROR_INTERVAL, MAXIMUM_ERROR_INTERVAL);
        _errorLogTimeInterval = errorLogTimeInterval;
    }

    /**
     * @see #setHistoryLength(int)
     * @return the number of events to retain in the <code>History</code> per
     *         category.
     */
    @Override
    public int getHistoryLength() {
        return _historyLength;
    }

    /**
     * @param name
     *            Category name
     * @return the <code>History</code> for that category or <code>null</code>
     *         if the specified category has no <code>History</code>.
     */
    public synchronized History getHistory(String name) {
        return _historyMap.get(name);
    }

    /**
     * Set the number of <code>Event</code>s per category to keep in the
     * <code>History</code>. Once the number of events exceeds this count,
     * earlier events are removed when new events are added. (However, the first
     * <code>Event</code> added is retained and get accessed via
     * {@link History#getFirstEvent()} even when subsequent <code>Event</code>s
     * are removed.) The default value is 10.
     * 
     * @param historyLength
     *            the historyLength to set
     */
    @Override
    public synchronized void setHistoryLength(int historyLength) {
        Util.rangeCheck(historyLength, MINIMUM_HISTORY_LENGTH, MAXIMUM_HISTORY_LENGTH);
        _historyLength = historyLength;
        for (final History history : _historyMap.values()) {
            history.trim(historyLength);
        }
    }

    /**
     * Called periodically to emit any pending log messages.
     * 
     * @param force
     *            Indicates whether to emit pending log messages and
     *            notifications prior to expiration of their respective
     *            notification time intervals.
     */
    @Override
    public synchronized void poll(final boolean force) {
        for (final History history : _historyMap.values()) {
            history.poll(System.currentTimeMillis(), force);
        }
    }

    /**
     * @return A human-readable summary of all <code>History</code> instances.
     */
    @Override
    public synchronized String toString() {
        StringBuilder sb = new StringBuilder();
        for (final Map.Entry<String, History> entry : _historyMap.entrySet()) {
            sb.append(String.format("%12s: %s\n", entry.getKey(), entry.getValue()));
        }
        return sb.toString();
    }

    /**
     * @see #toString()
     * @return A human-readable summary of all <code>History</code> instances.
     */
    @Override
    public String getSummary() {
        return toString();
    }

    /**
     * @return a detailed history report for the specified category
     * @param select
     *            The category name(s) to include, optionally with wildcards '*'
     *            and '?'
     */
    @Override
    public synchronized String getDetailedHistory(final String select) {
        Pattern pattern = Util.pattern(select, true);
        StringBuilder sb = new StringBuilder();
        for (final Map.Entry<String, History> entry : _historyMap.entrySet()) {
            if (pattern.matcher(entry.getKey()).matches()) {
                sb.append(String.format("%s:\n", entry.getKey()));
                sb.append(entry.getValue().getDetailedHistory());
            }
        }
        return sb.toString();
    }

    /**
     * @return the String value of the highest current {@link AlertLevel} among
     *         all categories being maintained
     */
    @Override
    public synchronized String getAlertLevel() {
        AlertLevel level = AlertLevel.NORMAL;
        for (final Map.Entry<String, History> entry : _historyMap.entrySet()) {
            if (entry.getValue().getLevel().compareTo(level) > 0) {
                level = entry.getValue().getLevel();
            }
        }
        return level.toString();
    }

    /**
     * @return the metadata needed by an MBeanServer to know what notifications
     *         may be sent by this MBean
     */
    @Override
    public MBeanNotificationInfo[] getNotificationInfo() {
        String[] types = new String[] { NOTIFICATION_TYPE };
        String name = Notification.class.getName();
        String description = "Alert raised by Akiban PersistIT";
        MBeanNotificationInfo info = new MBeanNotificationInfo(types, name, description);
        return new MBeanNotificationInfo[] { info };
    }

    /**
     * Emit a log message. If there has been only one Event, log it as a
     * standard log message. If there have been multiple Events, emit a
     * recurring event message.
     * 
     * @param history
     */
    private void log(History history) {
        final Event event = history.getLastEvent();
        if (event != null && event.getLogItem().isEnabled()) {
            if (history.getCount() == 1) {
                event.getLogItem().log(event.getArgs());
            } else {
                event.getLogItem().logRecurring(history.getCount(), history.getDuration(), event.getArgs());
            }
        }
    }

    /**
     * Broadcast a JMX Notification.
     * 
     * @param history
     */
    private void sendNotification(History history) {
        final Event event = history.getLastEvent();
        if (event != null && event.getLogItem().isEnabled()) {
            final String description = LogBase.recurring(event.getLogItem().logMessage(event.getArgs()), history
                    .getCount(), history.getDuration());
            Notification notification = new Notification(NOTIFICATION_TYPE, getClass().getName(), _notificationSequence
                    .incrementAndGet(), description);
            sendNotification(notification);
        }
    }

    /**
     * <p>
     * Format an event object as a String. By default this method returns a
     * String containing the time of the event formatted as a compact Date/Time
     * conversion followed by the event arguments concatenated in a
     * comma-limited list. For example,
     * 
     * <code><pre>
     *    2012-01-13 16:52:05 SomeException, SomeInfo1, SomeInfo2
     * </pre></code>
     * 
     * for an event that happened on March 13, 2012 at 4:25:05pm for which there
     * were three argument elements.
     * </p>
     * 
     * @param event
     * @return
     */
    private String format(Event event) {
        return event == null ? "null" : event.toString();
    }

}
