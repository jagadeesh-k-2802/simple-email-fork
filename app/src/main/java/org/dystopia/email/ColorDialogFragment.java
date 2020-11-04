package org.dystopia.email;

/*
    This file is part of SimpleEmail.

    SimpleEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    SimpleEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with SimpleEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018, Distopico (dystopia project) <distopico@riseup.net> and contributors
*/

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentResultListener;

import com.flask.colorpicker.ColorPickerView;
import com.flask.colorpicker.OnColorChangedListener;
import com.flask.colorpicker.builder.ColorPickerClickListener;
import com.flask.colorpicker.builder.ColorPickerDialogBuilder;

import static android.app.Activity.RESULT_OK;

public class ColorDialogFragment extends DialogFragment {
  private static int requestSequence = 0;
  private boolean sent = false;
  private String requestKey = null;
  private String targetRequestKey;
  private int targetRequestCode;
  private int color;

  public String getRequestKey() {
    if (requestKey == null)
      requestKey = getClass().getName() + "_" + (++requestSequence);
    return requestKey;
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    if (savedInstanceState != null) {
      requestKey = savedInstanceState.getString("dialog:request");
      targetRequestKey = savedInstanceState.getString("dialog:key");
      targetRequestCode = savedInstanceState.getInt("dialog:code");
    }

    getParentFragmentManager().setFragmentResultListener(getRequestKey(), this, new FragmentResultListener() {
      @Override
      public void onFragmentResult(@NonNull String requestKey, @NonNull Bundle result) {
        try {
          result.setClassLoader(ApplicationEx.class.getClassLoader());
          int requestCode = result.getInt("requestCode");
          int resultCode = result.getInt("resultCode");

          Intent data = new Intent();
          data.putExtra("args", result);
          onActivityResult(requestCode, resultCode, data);
        } catch (Throwable ex) {
          // LOg
        }
      }
    });
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    outState.putInt("dialog:color", color);
    outState.putString("dialog:request", requestKey);
    outState.putString("dialog:key", targetRequestKey);
    outState.putInt("dialog:code", targetRequestCode);
    super.onSaveInstanceState(outState);
  }

  @NonNull
  @Override
  public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
    Bundle args = getArguments();
    color = savedInstanceState == null ? args.getInt("color") : savedInstanceState.getInt("dialog:color");
    String title = args.getString("title");
    boolean reset = args.getBoolean("reset", false);

    Context context = getContext();
    int editTextColor = Helper.resolveColor(context, android.R.attr.editTextColor);

    ColorPickerDialogBuilder builder = ColorPickerDialogBuilder
      .with(context)
      .setTitle(title)
      .showColorEdit(true)
      .setColorEditTextColor(editTextColor)
      .wheelType(ColorPickerView.WHEEL_TYPE.FLOWER)
      .density(6)
      .lightnessSliderOnly()
      .setOnColorChangedListener(new OnColorChangedListener() {
          @Override
          public void onColorChanged(int selectedColor) {
            color = selectedColor;
          }
        })
      .setPositiveButton(android.R.string.ok, new ColorPickerClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int selectedColor, Integer[] allColors) {
            getArguments().putInt("color", selectedColor);
            sendResult(RESULT_OK);
          }
        });

    if (color != Color.TRANSPARENT) {
      builder.initialColor(color);
    }

    if (reset) {
      builder.setNegativeButton(R.string.title_reset, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            getArguments().putInt("color", Color.TRANSPARENT);
            sendResult(RESULT_OK);
          }
        });
    }

    return builder.build();
  }

  protected void sendResult(int resultCode) {
      if (sent) {
        return;
      }
      sent = true;
      if (targetRequestKey != null) {
        Bundle args = getArguments();
        if (args == null) {
          args = new Bundle();
        }
        args.putInt("requestCode", targetRequestCode);
        args.putInt("resultCode", resultCode);
        getParentFragmentManager().setFragmentResult(targetRequestKey, args);
      }
  }
}
