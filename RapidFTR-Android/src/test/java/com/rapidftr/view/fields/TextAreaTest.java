package com.rapidftr.view.fields;

import android.app.Activity;
import android.view.LayoutInflater;
import com.rapidftr.CustomTestRunner;
import com.rapidftr.R;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertThat;

@RunWith(CustomTestRunner.class)
public class TextAreaTest extends BaseViewSpec<TextArea> {

    @Before
    public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).create().get();
        view = (TextArea) activity.getLayoutInflater().inflate(R.layout.form_textarea, null);
    }

    @Test
    public void testBehavesLikeTextField() {
        assertThat(view, instanceOf(TextField.class));
    }

}
