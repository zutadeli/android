package org.owntracks.android.support.widgets;

import android.content.Context;

import android.util.AttributeSet;

import org.owntracks.android.App;
import org.owntracks.android.support.Preferences;

public class EditIntegerPreference extends EditStringPreference {

    public EditIntegerPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public EditIntegerPreference(Context context) {
        super(context);
    }

    @Override
    protected boolean persistString(String value) {
        if(value == null || "".equals(value)) {
            // Todo: improve
            App.getPreferences().clearKey(getKey());
            return true;
        }
        try {
            return persistInt(Integer.valueOf(value));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    protected String getPersistedString(String defaultReturnValue) {
        if(getSharedPreferences().contains(getKey())) {
            int intValue = getPersistedInt(0);
            return String.valueOf(intValue);
        } else {
            return defaultReturnValue;
        }
    }

}
