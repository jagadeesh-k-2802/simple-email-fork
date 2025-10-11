package org.dystopia.email;

/*
    This file is part of SimpleEmail.
    Copyright 2018-2020, Distopico (dystopia project) <distopico@riseup.net> and contributors

    This program  is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program  is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.
*/

import androidx.room.Entity;

@Entity
public class TupleNotification extends EntityMessage {
    public String accountName;
    public Integer accountColor;
    public String folderName;
    public String folderDisplay;
    public String folderType;

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof TupleMessageEx) {
            TupleMessageEx other = (TupleMessageEx) obj;
            return (super.equals(obj)
                && (this.accountName == null
                ? other.accountName == null
                : this.accountName.equals(other.accountName))
                && (this.accountColor == null
                ? other.accountColor == null
                : this.accountColor.equals(other.accountColor))
                && this.folderName.equals(other.folderName)
                && (this.folderDisplay == null
                ? other.folderDisplay == null
                : this.folderDisplay.equals(other.folderDisplay))
                && this.folderType.equals(other.folderType));
        }
        return super.equals(obj);
    }
}
