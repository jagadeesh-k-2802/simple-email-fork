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

import android.content.Context;

public class ViewHelper {

  /**
   * Convert density-independent pixels units to pixel units.
   * @param context - android content context to get density
   * @param dp - density-independent pixel value
   */
  static int dp2px(Context context, int dp) {
    float scale = context.getResources().getDisplayMetrics().density;
    return Math.round(dp * scale);
  }

  /**
   * Convert pixel units to density-independent pixels units.
   * @param context - android content context to get density
   * @param px - pixels value
   */
  static int px2dp(Context context, float px) {
    float scale = context.getResources().getDisplayMetrics().density;
    return Math.round(px / scale);
  }
}
