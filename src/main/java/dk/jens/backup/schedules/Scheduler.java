package dk.jens.backup.schedules;

import android.app.AlarmManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import com.annimon.stream.Optional;
import dk.jens.backup.BaseActivity;
import dk.jens.backup.BlacklistContract;
import dk.jens.backup.BlacklistListener;
import dk.jens.backup.BlacklistsDBHelper;
import dk.jens.backup.Constants;
import dk.jens.backup.FileCreationHelper;
import dk.jens.backup.FileReaderWriter;
import dk.jens.backup.R;
import dk.jens.backup.Utils;
import dk.jens.backup.schedules.db.Schedule;
import dk.jens.backup.schedules.db.ScheduleDao;
import dk.jens.backup.schedules.db.ScheduleDatabase;
import dk.jens.backup.schedules.db.ScheduleDatabaseHelper;
import dk.jens.backup.ui.dialogs.BlacklistDialogFragment;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

public class Scheduler extends BaseActivity
implements View.OnClickListener, AdapterView.OnItemSelectedListener,
BlacklistListener
{
    private static final String TAG = Constants.TAG;
    public static final String SCHEDULECUSTOMLIST = "customlist";
    static final int CUSTOMLISTUPDATEBUTTONID = 1;
    static final int EXCLUDESYSTEMCHECKBOXID = 2;

    public static final int GLOBALBLACKLISTID = -1;

    static final String DATABASE_NAME = "schedules.db";

    ArrayList<View> viewList;
    HandleAlarms handleAlarms;

    int totalSchedules;

    SharedPreferences defaultPrefs;
    SharedPreferences prefs;
    SharedPreferences.Editor edit;

    private BlacklistsDBHelper blacklistsDBHelper;

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.schedulesframe);

        handleAlarms = new HandleAlarms(this);

        defaultPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs = getSharedPreferences(Constants.PREFS_SCHEDULES, 0);
        edit = prefs.edit();

        viewList = new ArrayList<View>();
        blacklistsDBHelper = new BlacklistsDBHelper(this);
    }
    @Override
    public void onResume()
    {
        super.onResume();
        LinearLayout main = (LinearLayout) findViewById(R.id.linearLayout);
        totalSchedules = prefs.getInt(Constants.PREFS_SCHEDULES_TOTAL, 0);
        totalSchedules = totalSchedules < 0 ? 0 : totalSchedules; // set to zero so there is always at least one schedule on activity start
        for(View view : viewList)
        {
            android.view.ViewGroup parent = (android.view.ViewGroup) view.getParent();
            if(parent != null)
                parent.removeView(view);
        }
        viewList = new ArrayList<View>();
        for(int i = 0; i <= totalSchedules; i++)
        {
            try {
                View v = buildUi(prefs, i);
                viewList.add(v);
                main.addView(v);
                if(prefs.getBoolean(Constants.PREFS_SCHEDULES_ENABLED + i, false))
                    setTimeLeftTextView(i);
            } catch (SchedulingException e) {
                Toast.makeText(this, String.format(
                    "Unable to add schedule: %s", e.toString()),
                    Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onDestroy() {
        blacklistsDBHelper.close();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        menu.clear();
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.schedulesmenu, menu);
        return true;
    }
    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        switch(item.getItemId())
        {
            case R.id.addSchedule:
                try {
                    View v = buildUi(prefs, ++totalSchedules);
                    viewList.add(v);
                    ((LinearLayout) findViewById(R.id.linearLayout)).addView(v);
                    edit.putInt(Constants.PREFS_SCHEDULES_TOTAL, totalSchedules);
                    edit.commit();
                } catch (SchedulingException e) {
                    Toast.makeText(this, String.format(
                        "Unable to add schedule: %s", e.toString()),
                        Toast.LENGTH_LONG).show();
                }
                return true;
            case R.id.globalBlacklist:
                new Thread(() -> {
                    Bundle args = new Bundle();
                    args.putInt(Constants.BLACKLIST_ARGS_ID, GLOBALBLACKLISTID);
                    SQLiteDatabase db = blacklistsDBHelper.getReadableDatabase();
                    ArrayList<String> blacklistedPackages = blacklistsDBHelper
                        .getBlacklistedPackages(db, GLOBALBLACKLISTID);
                    args.putStringArrayList(Constants.BLACKLIST_ARGS_PACKAGES,
                        blacklistedPackages);
                    BlacklistDialogFragment blacklistDialogFragment = new BlacklistDialogFragment();
                    blacklistDialogFragment.setArguments(args);
                    blacklistDialogFragment.addBlacklistListener(this);
                    blacklistDialogFragment.show(getFragmentManager(), "blacklistDialog");
                }).start();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private View buildUiForNewSchedule(String databasename) {
        final Schedule schedule = new Schedule.Builder()
            // Set id to 0 to make the database generate a new id
            .withId(0)
            .build();
        final ScheduleDatabase scheduleDatabase = ScheduleDatabaseHelper
            .getScheduleDatabase(this, databasename);
        final ScheduleDao scheduleDao = scheduleDatabase.scheduleDao();
        scheduleDao.insert(schedule);
        return buildUi(schedule);
    }

    public View buildUi(SharedPreferences preferences, int number)
            throws SchedulingException {
        final Schedule schedule = Schedule.fromPreferences(
            preferences, number);
        return buildUi(schedule);
    }

    public View buildUi(Schedule schedule) {
        View view = LayoutInflater.from(this).inflate(R.layout.schedule, null);
        LinearLayout ll = (LinearLayout) view.findViewById(R.id.ll);

        Button updateButton = (Button) view.findViewById(R.id.updateButton);
        updateButton.setOnClickListener(this);
        Button removeButton = (Button) view.findViewById(R.id.removeButton);
        removeButton.setOnClickListener(this);
        Button activateButton = (Button) view.findViewById(R.id.activateButton);
        activateButton.setOnClickListener(this);
        EditText intervalDays = (EditText) view.findViewById(R.id.intervalDays);
        final String repeatString = Integer.toString(
            schedule.getInterval());
        intervalDays.setText(repeatString);
        EditText timeOfDay = (EditText) view.findViewById(R.id.timeOfDay);
        final String timeOfDayString = Integer.toString(
            schedule.getHour());
        timeOfDay.setText(timeOfDayString);
        CheckBox cb = (CheckBox) view.findViewById(R.id.checkbox);
        cb.setChecked(schedule.isEnabled());
        Spinner spinner = (Spinner) view.findViewById(R.id.sched_spinner);
        Spinner spinnerSubModes = (Spinner) view.findViewById(R.id.sched_spinnerSubModes);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, R.array.scheduleModes, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        // false has the effect that onItemSelected() is not called when
        // the spinner is added
        spinner.setSelection(schedule.getMode().getValue(), false);
        spinner.setOnItemSelectedListener(this);
        ArrayAdapter<CharSequence> adapterSubModes = ArrayAdapter.createFromResource(this, R.array.scheduleSubModes, android.R.layout.simple_spinner_item);
        adapterSubModes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSubModes.setAdapter(adapterSubModes);
        spinnerSubModes.setSelection(schedule.getSubmode().getValue(),
            false);
        spinnerSubModes.setOnItemSelectedListener(this);
        
        TextView timeLeftTextView = (TextView) view.findViewById(R.id.sched_timeLeft);

        final int number = (int) schedule.getId();
        toggleSecondaryButtons(ll, spinner, number);

        updateButton.setTag(number);
        removeButton.setTag(number);
        activateButton.setTag(number);
        cb.setTag(number);
        spinner.setTag(number);
        spinnerSubModes.setTag(number);

        return view;
    }
    public void checkboxOnClick(View v)
    {
        final int number = (int) v.getTag();
        try {
            final View scheduleView = viewList.get(number);
            final Schedule schedule = getScheduleDataFromView(
                scheduleView, number);
            schedule.persist(prefs);
            if(!schedule.isEnabled()) {
                handleAlarms.cancelAlarm(number);
            }
        } catch (SchedulingException e) {
            final String message = String.format(
                "Unable to enable schedule %s: %s", number, e.toString());
            Log.e(TAG, message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
        setTimeLeftTextView(number);
    }
    public void onClick(View v)
    {
        int number = (Integer) v.getTag();
        try
        {
            View view = viewList.get(number);
            switch(v.getId())
            {
                case EXCLUDESYSTEMCHECKBOXID:
                case R.id.updateButton:
                    updateScheduleData(view, number);
                    break;
                case R.id.removeButton:
                    try {
                        handleAlarms.cancelAlarm(number);
                        removePreferenceEntries(prefs, number);
                        removeCustomListFile(number);
                        ((LinearLayout) findViewById(R.id.linearLayout)).removeView(view);
                        migrateSchedules(number, totalSchedules);
                        viewList.remove(number);
                        edit.putInt(Constants.PREFS_SCHEDULES_TOTAL, --totalSchedules);
                        edit.commit();
                    } catch (SchedulingException e) {
                        final String message = String.format(
                            "Error removing schedule %s: %s", number,
                            e.toString());
                        Log.e(TAG, message);
                        Toast.makeText(this, message, Toast.LENGTH_LONG)
                            .show();
                    }
                    break;
                case R.id.activateButton:
                    Utils.showConfirmDialog(this, "", getString(R.string.sched_activateButton),
                        new StartSchedule(this, prefs, number));
                    break;
                case CUSTOMLISTUPDATEBUTTONID:
                    CustomPackageList.showList(this, number);
                    break;
            }
        }
        catch(IndexOutOfBoundsException e)
        {
            e.printStackTrace();
        }
    }

    private void updateScheduleData(View scheduleView, int id) {
        try {
            final Schedule schedule = getScheduleDataFromView(
                scheduleView, id);
            schedule.persist(prefs);
            setTimeLeftTextView(id);
        } catch (SchedulingException e) {
            Log.e(TAG, String.format("Unable to update schedule %s",
                id));
            Toast.makeText(this, String.format(
                "Unable to update schedule %s", id),
                Toast.LENGTH_LONG).show();
        }
    }

    private Schedule getScheduleDataFromView(View scheduleView, int id)
            throws SchedulingException {
        final EditText intervalText = scheduleView.findViewById(
            R.id.intervalDays);
        final EditText hourText = scheduleView.findViewById(
            R.id.timeOfDay);
        final Spinner modeSpinner = scheduleView.findViewById(
            R.id.sched_spinner);
        final Spinner submodeSpinner = scheduleView.findViewById(
            R.id.sched_spinnerSubModes);
        final CheckBox excludeSystemCheckbox = scheduleView.findViewById(
            EXCLUDESYSTEMCHECKBOXID);
        final boolean excludeSystemPackages = excludeSystemCheckbox != null
            && excludeSystemCheckbox.isChecked();

        final CheckBox enabledCheckbox = scheduleView.findViewById(
            R.id.checkbox);
        final boolean enabled = enabledCheckbox.isChecked();
        final int hour = Integer.parseInt(hourText.getText()
            .toString());
        final int interval = Integer.parseInt(intervalText
            .getText().toString());
        long nextEvent = 0;
        if (enabled) {
            nextEvent = HandleAlarms.timeUntilNextEvent(
                interval, hour, true);
            handleAlarms.setAlarm(id, nextEvent,
                interval * AlarmManager.INTERVAL_DAY);
        }
        return new Schedule.Builder()
            .withId(id)
            .withHour(hour)
            .withInterval(interval)
            .withMode(modeSpinner.getSelectedItemPosition())
            .withSubmode(submodeSpinner.getSelectedItemPosition())
            .withPlaced(System.currentTimeMillis())
            .withEnabled(enabled)
            .withTimeUntilNextEvent(nextEvent)
            .withExcludeSystem(excludeSystemPackages)
            .build();
    }

    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id)
    {
        int number = (Integer) parent.getTag();
        final int spinnerId = parent.getId();
        if(spinnerId == R.id.sched_spinner) {
            toggleSecondaryButtons((LinearLayout) parent.getParent(), (Spinner) parent, number);
            if (pos == 4) {
                CustomPackageList.showList(this, number);
            }
            changeScheduleMode(pos, number);
        } else if(spinnerId == R.id.sched_spinnerSubModes) {
            changeScheduleSubmode(pos, number);
        }
    }
    public void onNothingSelected(AdapterView<?> parent)
    {}

    private void changeScheduleMode(int mode, int id) {
        try {
            final Schedule schedule = Schedule.fromPreferences(
                prefs, id);
            schedule.setMode(mode);
            schedule.persist(prefs);
        } catch (SchedulingException e) {
            final String message = String.format(
                "Unable to set mode of schedule %s to %s", id, mode);
            Log.e(TAG, message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    private void changeScheduleSubmode(int submode, int id) {
        try {
            final Schedule schedule = Schedule.fromPreferences(
                prefs, id);
            schedule.setSubmode(submode);
            schedule.persist(prefs);
        } catch (SchedulingException e) {
            final String message = String.format(
                "Unable to set submode of schedule %s to %s", id, submode);
            Log.e(TAG, message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBlacklistChanged(CharSequence[] blacklist, int id) {
        new Thread(() -> {
            SQLiteDatabase db = blacklistsDBHelper.getWritableDatabase();
            blacklistsDBHelper.deleteBlacklistFromId(db, id);
            for(CharSequence packagename : blacklist) {
                ContentValues values = new ContentValues();
                values.put(BlacklistContract.BlacklistEntry.COLUMN_PACKAGENAME, (String) packagename);
                values.put(BlacklistContract.BlacklistEntry.COLUMN_BLACKLISTID, String.valueOf(id));
                db.insert(BlacklistContract.BlacklistEntry.TABLE_NAME, null, values);
            }
        }).start();
    }

    public void setTimeLeftTextView(int number)
    {
        View view = viewList.get(number);
        if(view != null)
        {
            TextView timeLeftTextView = (TextView) view.findViewById(R.id.sched_timeLeft);
            long timePlaced = prefs.getLong(
                Constants.PREFS_SCHEDULES_TIMEPLACED + number, 0);
            long repeat = (long)(prefs.getInt(
                Constants.PREFS_SCHEDULES_REPEATTIME + number, 0) *
                AlarmManager.INTERVAL_DAY);
            long timePassed = System.currentTimeMillis() - timePlaced;
            long timeLeft = prefs.getLong(
                Constants.PREFS_SCHEDULES_TIMEUNTILNEXTEVENT + number, 0) - timePassed;
            if(!prefs.getBoolean(Constants.PREFS_SCHEDULES_ENABLED + number, false))
            {
                timeLeftTextView.setText("");
            }
            else if(repeat <= 0)
            {
                timeLeftTextView.setText(getString(R.string.sched_warningIntervalZero));
            }
            else
            {
                timeLeftTextView.setText(getString(R.string.sched_timeLeft) + ": " + (timeLeft / 1000f / 60 / 60f));
            }
        }
    }
    public void toggleSecondaryButtons(LinearLayout parent, Spinner spinner, int number)
    {
        switch(spinner.getSelectedItemPosition())
        {
            case 3:
                CheckBox cb = new CheckBox(this);
                cb.setId(EXCLUDESYSTEMCHECKBOXID);
                cb.setText(getString(R.string.sched_excludeSystemCheckBox));
                cb.setTag(number);
                cb.setChecked(prefs.getBoolean(Constants.PREFS_SCHEDULES_EXCLUDESYSTEM + number, false));
                cb.setOnClickListener(this);
                LayoutParams cblp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                parent.addView(cb, cblp);
                removeSecondaryButton(parent, cb);
                break;
            case 4:
                Button bt = new Button(this);
                bt.setId(CUSTOMLISTUPDATEBUTTONID);
                bt.setText(getString(R.string.sched_customListUpdateButton));
                bt.setTag(number);
                bt.setOnClickListener(this);
                LayoutParams btlp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                parent.addView(bt, btlp);
                removeSecondaryButton(parent, bt);
                break;
            default:
                removeSecondaryButton(parent, null);
                break;
        }
    }
    public void removeSecondaryButton(LinearLayout parent, View v)
    {
        int id = (v != null) ? v.getId() : -1;
        Button bt = (Button) parent.findViewById(CUSTOMLISTUPDATEBUTTONID);
        CheckBox cb = (CheckBox) parent.findViewById(EXCLUDESYSTEMCHECKBOXID);
        if(bt != null && id != CUSTOMLISTUPDATEBUTTONID)
        {
            parent.removeView(bt);
        }
        if(cb != null && id != EXCLUDESYSTEMCHECKBOXID)
        {
            parent.removeView(cb);
        }
    }

    void migrateSchedulesToDatabase(SharedPreferences preferences,
            String databasename) throws SchedulingException {
        final ScheduleDatabase scheduleDatabase = ScheduleDatabaseHelper
            .getScheduleDatabase(this, databasename);
        final ScheduleDao scheduleDao = scheduleDatabase.scheduleDao();
        for(int i = 0; i < totalSchedules; i++) {
            final Schedule schedule = Schedule.fromPreferences(preferences,
                i);
             // The database is one-indexed so in order to preserve the
             // order of the inserted schedules we have to increment the id.
            schedule.setId(i + 1L);
            try {
                final long[] ids = scheduleDao.insert(schedule);
                // TODO: throw an exception if renaming failed. This requires
                //  the renaming logic to propagate errors properly.
                renameCustomListFile(i, ids[0]);
                removePreferenceEntries(preferences, i);
                if (schedule.isEnabled()) {
                    handleAlarms.cancelAlarm(i);
                    handleAlarms.setAlarm((int) ids[0],
                        schedule.getTimeUntilNextEvent(), schedule.getInterval());
                }
            } catch (SQLException e) {
                throw new SchedulingException(
                    "Unable to migrate schedules to database", e);
            }
        }
    }

    public void migrateSchedules(int number, int total)
            throws SchedulingException {
        for(int i = number; i < total; i++)
        {
            // decrease alarm id by one if set
            if(prefs.getBoolean(Constants.PREFS_SCHEDULES_ENABLED + (i + 1), false))
            {
                long timePassed = System.currentTimeMillis() - prefs.getLong(
                    Constants.PREFS_SCHEDULES_TIMEPLACED + (i + 1), 0);
                long timeLeft = prefs.getLong(
                    Constants.PREFS_SCHEDULES_TIMEUNTILNEXTEVENT + (i + 1), 0) -
                    timePassed;
                long repeat = (long)(prefs.getInt(
                    Constants.PREFS_SCHEDULES_REPEATTIME + (i + 1), 0) *
                    AlarmManager.INTERVAL_DAY);
                handleAlarms.cancelAlarm(i + 1);
                handleAlarms.setAlarm(i, timeLeft, repeat);
            }

            // move settings one place back
            final Schedule schedule = Schedule.fromPreferences(
                prefs, number + 1);
            schedule.setId(number);
            schedule.persist(prefs);

            // update tags on view elements
            View view = viewList.get(i + 1);
            Button updateButton = (Button) view.findViewById(R.id.updateButton);
            Button removeButton = (Button) view.findViewById(R.id.removeButton);
            Button customListUpdateButton = (Button) view.findViewById(CUSTOMLISTUPDATEBUTTONID);
            CheckBox cb = (CheckBox) view.findViewById(R.id.checkbox);
            CheckBox excludeSystemCB = (CheckBox) view.findViewById(EXCLUDESYSTEMCHECKBOXID);
            Spinner spinner = (Spinner) view.findViewById(R.id.sched_spinner);
            Spinner spinnerSubModes = (Spinner) view.findViewById(R.id.sched_spinnerSubModes);

            updateButton.setTag(i);
            removeButton.setTag(i);
            cb.setTag(i);
            spinner.setTag(i);
            spinnerSubModes.setTag(i);
            if(customListUpdateButton != null)
            {
                customListUpdateButton.setTag(i);
            }
            if(excludeSystemCB != null)
            {
                excludeSystemCB.setTag(i);
            }
            
            renameCustomListFile(i + 1L, i);
        }
        removePreferenceEntries(prefs, total);
    }
    public void removePreferenceEntries(SharedPreferences preferences,
            int number) {
        final SharedPreferences.Editor editor = preferences.edit();
        editor.remove(Constants.PREFS_SCHEDULES_ENABLED + number);
        editor.remove(Constants.PREFS_SCHEDULES_EXCLUDESYSTEM + number);
        editor.remove(Constants.PREFS_SCHEDULES_HOUROFDAY + number);
        editor.remove(Constants.PREFS_SCHEDULES_REPEATTIME + number);
        editor.remove(Constants.PREFS_SCHEDULES_MODE + number);
        editor.remove(Constants.PREFS_SCHEDULES_SUBMODE + number);
        editor.remove(Constants.PREFS_SCHEDULES_TIMEPLACED + number);
        editor.remove(Constants.PREFS_SCHEDULES_TIMEUNTILNEXTEVENT + number);
        editor.apply();
    }
    public void renameCustomListFile(long id, long destinationId)
    {
        FileReaderWriter frw = new FileReaderWriter(defaultPrefs.getString(
            Constants.PREFS_PATH_BACKUP_DIRECTORY, FileCreationHelper
            .getDefaultBackupDirPath()), SCHEDULECUSTOMLIST + id);
        frw.rename(SCHEDULECUSTOMLIST + destinationId);
    }
    public void removeCustomListFile(int number)
    {
        FileReaderWriter frw = new FileReaderWriter(defaultPrefs.getString(
            Constants.PREFS_PATH_BACKUP_DIRECTORY, FileCreationHelper
            .getDefaultBackupDirPath()), SCHEDULECUSTOMLIST + number);
        frw.delete();
    }
    private class StartSchedule implements Utils.Command
    {
        Context context;
        SharedPreferences preferences;
        int scheduleNumber;
        public StartSchedule(Context context, SharedPreferences preferences, int scheduleNumber)
        {
            this.context = context;
            this.preferences = preferences;
            this.scheduleNumber = scheduleNumber;
        }
        public void execute()
        {
            int mode = preferences.getInt(Constants.PREFS_SCHEDULES_MODE + scheduleNumber, 0);
            int subMode = preferences.getInt(Constants.PREFS_SCHEDULES_SUBMODE + scheduleNumber, 2);
            boolean excludeSystem = preferences.getBoolean(
                Constants.PREFS_SCHEDULES_EXCLUDESYSTEM + scheduleNumber, false);
            HandleScheduledBackups handleScheduledBackups = new HandleScheduledBackups(context);
            handleScheduledBackups.initiateBackup(scheduleNumber, mode, subMode + 1, excludeSystem);
        }
    }

    static class AddScheduleTask extends AsyncTask<Void, Void, ResultHolder<View>> {
        // Use a weak reference to avoid leaking the activity if it's
        // destroyed while this task is still running.
        private final WeakReference<Scheduler> activityReference;
        private final String databasename;

        AddScheduleTask(Scheduler scheduler) {
            activityReference = new WeakReference<>(scheduler);
            databasename = DATABASE_NAME;
        }

        AddScheduleTask(Scheduler scheduler, String databasename) {
            activityReference = new WeakReference<>(scheduler);
            this.databasename = databasename;
        }

        @Override
        public ResultHolder<View> doInBackground(Void... _void) {
            final Scheduler scheduler = activityReference.get();
            if(scheduler == null || scheduler.isFinishing()) {
                return new ResultHolder<>();
            }
            return new ResultHolder<>(scheduler.buildUiForNewSchedule(
                databasename));
        }

        @Override
        public void onPostExecute(ResultHolder<View> resultHolder) {
            final Scheduler scheduler = activityReference.get();
            if(scheduler != null && !scheduler.isFinishing()) {
                resultHolder.getObject().ifPresent(view -> {
                    scheduler.viewList.add(view);
                    ((LinearLayout) scheduler.findViewById(R.id.linearLayout))
                        .addView(view);
                    scheduler.edit.putInt(Constants.PREFS_SCHEDULES_TOTAL,
                        scheduler.totalSchedules);
                    scheduler.edit.commit();
                });
            }
        }
    }

    private static class ResultHolder<T> {
        private final Optional<T> object;
        private final Optional<Throwable> error;

        ResultHolder() {
            object = Optional.empty();
            error = Optional.empty();
        }

        ResultHolder(T object) {
            this.object = Optional.of(object);
            error = Optional.empty();
        }

        ResultHolder(Throwable error) {
            this.error = Optional.of(error);
            object = Optional.empty();
        }

        Optional<T> getObject() {
            return object;
        }

        Optional<Throwable> getError() {
            return error;
        }
    }
}
