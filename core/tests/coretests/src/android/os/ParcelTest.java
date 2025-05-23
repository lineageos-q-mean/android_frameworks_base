/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;
import android.util.Log;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class ParcelTest {
    private static final int WORK_SOURCE_1 = 1000;
    private static final int WORK_SOURCE_2 = 1002;
    private static final String INTERFACE_TOKEN_1 = "IBinder interface token";
    private static final String INTERFACE_TOKEN_2 = "Another IBinder interface token";

    @Test
    public void testCallingWorkSourceUidAfterWrite() {
        Parcel p = Parcel.obtain();
        // Method does not throw if replaceCallingWorkSourceUid is called before requests headers
        // are added.
        assertEquals(false, p.replaceCallingWorkSourceUid(WORK_SOURCE_1));
        assertEquals(Binder.UNSET_WORKSOURCE, p.readCallingWorkSourceUid());

        // WorkSource can be updated.
        p.writeInterfaceToken(INTERFACE_TOKEN_1);
        assertEquals(true, p.replaceCallingWorkSourceUid(WORK_SOURCE_1));
        assertEquals(WORK_SOURCE_1, p.readCallingWorkSourceUid());

        // WorkSource can be updated to unset value.
        assertEquals(true, p.replaceCallingWorkSourceUid(Binder.UNSET_WORKSOURCE));
        assertEquals(Binder.UNSET_WORKSOURCE, p.readCallingWorkSourceUid());

        p.recycle();
    }

    @Test
    public void testCallingWorkSourceUidAfterEnforce() {
        Parcel p = Parcel.obtain();
        // Write headers manually so that we do not invoke #writeInterfaceToken.
        p.writeInt(1);  // strict mode header
        p.writeInt(WORK_SOURCE_1);  // worksource header.
        p.writeString(INTERFACE_TOKEN_1);  // interface token.
        p.setDataPosition(0);

        p.enforceInterface(INTERFACE_TOKEN_1);
        assertEquals(WORK_SOURCE_1, p.readCallingWorkSourceUid());

        // WorkSource can be updated.
        assertEquals(true, p.replaceCallingWorkSourceUid(WORK_SOURCE_1));
        assertEquals(WORK_SOURCE_1, p.readCallingWorkSourceUid());

        p.recycle();
    }

    @Test
    public void testParcelWithMultipleHeaders() {
        Parcel p = Parcel.obtain();
        Binder.setCallingWorkSourceUid(WORK_SOURCE_1);
        p.writeInterfaceToken(INTERFACE_TOKEN_1);
        Binder.setCallingWorkSourceUid(WORK_SOURCE_2);
        p.writeInterfaceToken(INTERFACE_TOKEN_2);
        p.setDataPosition(0);

        // WorkSource is from the first header.
        p.enforceInterface(INTERFACE_TOKEN_1);
        assertEquals(WORK_SOURCE_1, p.readCallingWorkSourceUid());
        p.enforceInterface(INTERFACE_TOKEN_2);
        assertEquals(WORK_SOURCE_1, p.readCallingWorkSourceUid());

        p.recycle();
    }

    @Test
    public void testClassCookies() {
        Parcel p = Parcel.obtain();
        assertThat(p.hasClassCookie(ParcelTest.class)).isFalse();

        p.setClassCookie(ParcelTest.class, "string_cookie");
        assertThat(p.hasClassCookie(ParcelTest.class)).isTrue();
        assertThat(p.getClassCookie(ParcelTest.class)).isEqualTo("string_cookie");

        p.removeClassCookie(ParcelTest.class, "string_cookie");
        assertThat(p.hasClassCookie(ParcelTest.class)).isFalse();
        assertThat(p.getClassCookie(ParcelTest.class)).isEqualTo(null);

        p.setClassCookie(ParcelTest.class, "to_be_discarded_cookie");
        p.recycle();
        assertThat(p.getClassCookie(ParcelTest.class)).isNull();
    }

    @Test
    public void testClassCookies_removeUnexpected() {
        Parcel p = Parcel.obtain();

        assertLogsWtf(() -> p.removeClassCookie(ParcelTest.class, "not_present"));

        p.setClassCookie(ParcelTest.class, "value");

        assertLogsWtf(() -> p.removeClassCookie(ParcelTest.class, "different"));
        assertThat(p.getClassCookie(ParcelTest.class)).isNull(); // still removed

        p.recycle();
    }

    private static void assertLogsWtf(Runnable test) {
        ArrayList<Log.TerribleFailure> wtfs = new ArrayList<>();
        Log.TerribleFailureHandler oldHandler = Log.setWtfHandler(
                (tag, what, system) -> wtfs.add(what));
        try {
            test.run();
        } finally {
            Log.setWtfHandler(oldHandler);
        }
        assertThat(wtfs).hasSize(1);
    }
}
