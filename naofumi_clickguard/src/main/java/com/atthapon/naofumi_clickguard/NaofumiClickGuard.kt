package com.atthapon.naofumi_clickguard

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.View.OnClickListener

import java.lang.reflect.Field

/**
 * Class used to guard a view to avoid multiple rapid clicks.
 *
 *
 * Guarding a view is as easy as:
 * <pre>`
 * ClickGuard.guard(view);
`</pre> *
 *
 *
 * Or:
 * <pre>`
 * ClickGuard.newGuard().add(view);
`</pre> *
 *
 *
 * When a guarded view is clicked, the view will be watched for a period of time from that moment.
 * All the upcoming click events will be ignored until the watch period ends.
 *
 *
 * By default, watch period is 1000 milliseconds. You can create a ClickGuard using specify watch
 * period like this:
 * <pre>`
 * ClickGuard.newGuard(600); // Create a ClickGuard with 600ms watch period.
`</pre> *
 *
 *
 * Multiple views can be guarded by a ClickGuard simultaneously:
 * <pre>`
 * ClickGuard.guard(view1, view2, view3);
`</pre> *
 *
 *
 * When multiple views are guarded by one ClickGuard, the first click on a view will trigger this
 * ClickGuard to watch. And all upcoming clicks on any of the guarded views will be ignored until
 * the watch period ends.
 *
 *
 * Another way to guard a view is using a [GuardedOnClickListener]
 * instead of [OnClickListener][android.view.View.OnClickListener]:
 * <pre>`
 * button.setOnClickListener(new GuardedOnClickListener() {
 * @Override
 * public boolean onClicked() {
 * // React to button click.
 * return true;
 * }
 * });
`</pre> *
 *
 *
 * Using static [wrap][.wrap] method can simply make an
 * exist [OnClickListener][android.view.View.OnClickListener] to be a [ ]:
 * <pre>`
 * button.setOnClickListener(ClickGuard.wrap(onClickListener));
`</pre> *
 */
abstract class NaofumiClickGuard private constructor() {

    /**
     * Determine whether the Guard is on duty.
     *
     * @return Whether the Guard is watching.
     */
    abstract val isWatching: Boolean

    // ---------------------------------------------------------------------------------------------
    //                                  Utility methods end
    // ---------------------------------------------------------------------------------------------

    /**
     * Let a view to be guarded by this ClickGuard.
     *
     * @param view The view to be guarded.
     * @return This ClickGuard instance.
     * @see .addAll
     */
    fun add(view: View?): NaofumiClickGuard {
        if (view == null) {
            throw IllegalArgumentException("View shouldn't be null!")
        }
        val listener = retrieveOnClickListener(view) ?: throw IllegalStateException(
            "Haven't set an OnClickListener to View (id: 0x"
                    + Integer.toHexString(view.id) + ")!"
        )
        view.setOnClickListener(wrapOnClickListener(listener))
        return this
    }

    /**
     * Like [.add]. Let a series of views to be guarded by this ClickGuard.
     *
     * @param view   The view to be guarded.
     * @param others More views to be guarded.
     * @return This ClickGuard instance.
     * @see .add
     */
    fun addAll(view: View, vararg others: View): NaofumiClickGuard {
        add(view)
        for (v in others) {
            add(v)
        }
        return this
    }

    /**
     * Like [.add]. Let a series of views to be guarded by this ClickGuard.
     *
     * @param views The views to be guarded.
     * @return This ClickGuard instance.
     * @see .add
     */
    fun addAll(views: Iterable<View>): NaofumiClickGuard {
        for (v in views) {
            add(v)
        }
        return this
    }

    /**
     * Let the provided [android.view.View.OnClickListener] to be a [GuardedOnClickListener]
     * which will be guarded by this ClickGuard.
     *
     * @param onClickListener onClickListener
     * @return A GuardedOnClickListener instance.
     */
    fun wrapOnClickListener(onClickListener: OnClickListener?): GuardedOnClickListener {
        if (onClickListener == null) {
            throw IllegalArgumentException("onClickListener shouldn't be null!")
        }
        if (onClickListener is GuardedOnClickListener) {
            throw IllegalArgumentException("Can't wrap GuardedOnClickListener!")
        }
        return InnerGuardedOnClickListener(onClickListener, this)
    }

    /**
     * Let the Guard to start watching.
     */
    abstract fun watch()

    /**
     * Let the Guard to have a rest.
     */
    abstract fun rest()

    private class ClickGuardImpl internal constructor(private val mWatchPeriodMillis: Long) : NaofumiClickGuard() {
        private val mHandler = Handler(Looper.getMainLooper())

        override val isWatching: Boolean
            get() = mHandler.hasMessages(WATCHING)

        override fun watch() {
            mHandler.sendEmptyMessageDelayed(WATCHING, mWatchPeriodMillis)
        }

        override fun rest() {
            mHandler.removeMessages(WATCHING)
        }

        companion object {
            private const val WATCHING = 0
        }
    }

    /**
     * OnClickListener which can avoid multiple rapid clicks.
     */
    abstract class GuardedOnClickListener internal constructor(
        private val mWrapped: OnClickListener?,
        val clickGuard: NaofumiClickGuard
    ) : OnClickListener {

        @JvmOverloads
        constructor(watchPeriodMillis: Long = DEFAULT_WATCH_PERIOD_MILLIS) : this(newGuard(watchPeriodMillis))

        constructor(guard: NaofumiClickGuard) : this(null, guard)

        override fun onClick(v: View) {
            if (clickGuard.isWatching) {
                // Guard is guarding, can't do anything.
                onIgnored()
                return
            }
            // Guard is relaxing. Run!
            mWrapped?.onClick(v)
            if (onClicked()) {
                // Guard becomes vigilant.
                clickGuard.watch()
            }
        }

        /**
         * Called when a click is allowed.
         *
         * @return If `true` is returned, the host view will be guarded. All click events in
         * the upcoming watch period will be ignored. Otherwise, the next click will not be ignored.
         */
        abstract fun onClicked(): Boolean

        /**
         * Called when a click is ignored.
         */
        open fun onIgnored() {}
    }

    // Inner GuardedOnClickListener implementation.
    internal class InnerGuardedOnClickListener(onClickListener: OnClickListener, guard: NaofumiClickGuard) :
        GuardedOnClickListener(onClickListener, guard) {

        override fun onClicked(): Boolean {
            return true
        }

        override fun onIgnored() {}
    }

    /**
     * Class used for retrieve OnClickListener from a View.
     */
    internal abstract class ListenerGetter {

        internal abstract fun getOnClickListener(view: View): OnClickListener?

        private class ListenerGetterBase internal constructor() : ListenerGetter() {
            private val mOnClickListenerField: Field = getField(View::class.java, "mOnClickListener")

            public override fun getOnClickListener(view: View): OnClickListener? {
                return getFieldValue(mOnClickListenerField, view) as? OnClickListener
            }
        }

        private class ListenerGetterIcs internal constructor() : ListenerGetter() {
            private val mListenerInfoField: Field = getField(View::class.java, "mListenerInfo")
            private val mOnClickListenerField: Field

            init {
                mListenerInfoField.isAccessible = true
                mOnClickListenerField = getField("android.view.View\$ListenerInfo", "mOnClickListener")
            }

            public override fun getOnClickListener(view: View): OnClickListener? {
                val listenerInfo = getFieldValue(mListenerInfoField, view)
                return if (listenerInfo != null)
                    getFieldValue(mOnClickListenerField, listenerInfo) as? OnClickListener
                else
                    null
            }
        }

        companion object {

            private var IMPL: ListenerGetter? = null

            init {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                    IMPL = ListenerGetterIcs()
                } else {
                    IMPL = ListenerGetterBase()
                }
            }

            operator fun get(view: View): OnClickListener? {
                return IMPL?.getOnClickListener(view)
            }

            fun getField(clazz: Class<*>, fieldName: String): Field {
                try {
                    return clazz.getDeclaredField(fieldName)
                } catch (ignored: NoSuchFieldException) {
                    throw RuntimeException("Can't get " + fieldName + " of " + clazz.name)
                }

            }

            fun getField(className: String, fieldName: String): Field {
                try {
                    return getField(Class.forName(className), fieldName)
                } catch (ignored: ClassNotFoundException) {
                    throw RuntimeException("Can't find class: $className")
                }

            }

            fun getFieldValue(field: Field, `object`: Any): Any? {
                try {
                    return field.get(`object`)
                } catch (ignored: IllegalAccessException) {
                }

                return null
            }
        }
    }

    companion object {

        /**
         * Default watch period in millis.
         */
        const val DEFAULT_WATCH_PERIOD_MILLIS = 1000L

        /**
         * Utility method. Create a ClickGuard with specific watch period: `watchPeriodMillis`.
         *
         * @return The created ClickGuard instance.
         */
        @JvmOverloads
        fun newGuard(watchPeriodMillis: Long = DEFAULT_WATCH_PERIOD_MILLIS): NaofumiClickGuard {
            return ClickGuardImpl(watchPeriodMillis)
        }

        /**
         * Utility method. Let the provided [OnClickListener][android.view.View.OnClickListener]
         * to be a [GuardedOnClickListener]. Use a new guard with default
         * watch period: [.DEFAULT_WATCH_PERIOD_MILLIS].
         *
         * @param onClickListener The listener to be wrapped.
         * @return A GuardedOnClickListener instance.
         */
        fun wrap(onClickListener: OnClickListener): GuardedOnClickListener {
            return wrap(newGuard(), onClickListener)
        }

        /**
         * Utility method. Let the provided [OnClickListener][android.view.View.OnClickListener]
         * to be a [GuardedOnClickListener]. Use a new guard with specific
         * watch period: `watchPeriodMillis`.
         *
         * @param watchPeriodMillis The specific watch period.
         * @param onClickListener   The listener to be wrapped.
         * @return A GuardedOnClickListener instance.
         */
        fun wrap(watchPeriodMillis: Long, onClickListener: OnClickListener): GuardedOnClickListener {
            return newGuard(watchPeriodMillis).wrapOnClickListener(onClickListener)
        }

        /**
         * Utility method. Let the provided [OnClickListener][android.view.View.OnClickListener]
         * to be a [GuardedOnClickListener]. Use specific ClickGuard:
         * `guard`.
         *
         * @param guard           The specific ClickGuard.
         * @param onClickListener The listener to be wrapped.
         * @return A GuardedOnClickListener instance.
         */
        fun wrap(guard: NaofumiClickGuard, onClickListener: OnClickListener): GuardedOnClickListener {
            return guard.wrapOnClickListener(onClickListener)
        }

        /**
         * Utility method. Use a new ClickGuard with default watch period [.DEFAULT_WATCH_PERIOD_MILLIS]
         * to guard View(s).
         *
         * @param view   The View to be guarded.
         * @param others More views to be guarded.
         * @return The created ClickedGuard.
         */
        fun guard(view: View, vararg others: View): NaofumiClickGuard {
            return guard(DEFAULT_WATCH_PERIOD_MILLIS, view, *others)
        }

        /**
         * Utility method. Use a new ClickGuard with specific guard period `watchPeriodMillis` to
         * guard View(s).
         *
         * @param watchPeriodMillis The specific watch period.
         * @param view              The View to be guarded.
         * @param others            More Views to be guarded.
         * @return The created ClickedGuard.
         */
        fun guard(watchPeriodMillis: Long, view: View, vararg others: View): NaofumiClickGuard {
            return guard(newGuard(watchPeriodMillis), view, *others)
        }

        /**
         * Utility method. Use a specific ClickGuard `guard` to guard View(s).
         *
         * @param guard  The ClickGuard used to guard.
         * @param view   The View to be guarded.
         * @param others More Views to be guarded.
         * @return The given ClickedGuard itself.
         */
        fun guard(guard: NaofumiClickGuard, view: View, vararg others: View): NaofumiClickGuard {
            return guard.addAll(view, *others)
        }

        /**
         * Utility method. Use a new ClickGuard with default watch period [.DEFAULT_WATCH_PERIOD_MILLIS]
         * to guard a series of Views.
         *
         * @param views The Views to be guarded.
         * @return The created ClickedGuard.
         */
        fun guardAll(views: Iterable<View>): NaofumiClickGuard {
            return guardAll(DEFAULT_WATCH_PERIOD_MILLIS, views)
        }

        /**
         * Utility method. Use a new ClickGuard with specific guard period `watchPeriodMillis` to
         * guard a series of Views.
         *
         * @param watchPeriodMillis The specific watch period.
         * @param views             The Views to be guarded.
         * @return The created ClickedGuard.
         */
        fun guardAll(watchPeriodMillis: Long, views: Iterable<View>): NaofumiClickGuard {
            return guardAll(newGuard(watchPeriodMillis), views)
        }

        /**
         * Utility method. Use a specific ClickGuard `guard` to guard a series of Views.
         *
         * @param guard The ClickGuard used to guard.
         * @param views The Views to be guarded.
         * @return The given ClickedGuard itself.
         */
        fun guardAll(guard: NaofumiClickGuard, views: Iterable<View>): NaofumiClickGuard {
            return guard.addAll(views)
        }

        /**
         * Utility method. Get the ClickGuard from a guarded View.
         *
         * @param view A View guarded by ClickGuard.
         * @return The ClickGuard which guards this View.
         */
        operator fun get(view: View): NaofumiClickGuard {
            val listener = retrieveOnClickListener(view)
            if (listener is GuardedOnClickListener) {
                return listener.clickGuard
            }
            throw IllegalStateException("The view (id: 0x" + view.id + ") isn't guarded by ClickGuard!")
        }

        /**
         * Utility method. Retrieve [OnClickListener][android.view.View.OnClickListener] from
         * a View.
         *
         * @param view The View used to retrieve.
         * @return The retrieved [OnClickListener][android.view.View.OnClickListener].
         */
        fun retrieveOnClickListener(view: View?): OnClickListener? {
            if (view == null) {
                throw NullPointerException("Given view is null!")
            }
            return ListenerGetter[view]
        }
    }
}
// ---------------------------------------------------------------------------------------------
//                                  Utility methods start
// ---------------------------------------------------------------------------------------------
/**
 * Utility method. Create a ClickGuard with default watch period: [.DEFAULT_WATCH_PERIOD_MILLIS].
 *
 * @return The created ClickGuard instance.
 */