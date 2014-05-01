/*
Copyright (c) Microsoft Open Technologies, Inc.
All Rights Reserved
Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0

THIS CODE IS PROVIDED ON AN *AS IS* BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED,
INCLUDING WITHOUT LIMITATION ANY IMPLIED WARRANTIES OR CONDITIONS OF TITLE, FITNESS FOR A PARTICULAR PURPOSE,
MERCHANTABLITY OR NON-INFRINGEMENT.

See the Apache 2 License for the specific language governing permissions and limitations under the License.
*/

package com.msopentech.thali.utilities.android;

import android.content.Context;
import com.couchbase.lite.JavaContext;

import java.io.File;
import java.io.IOException;

/**
 * Creates a random temp directory to store the user's content in off the Android context
 * Work around for https://github.com/couchbase/couchbase-lite-java/issues/4 and
 * https://github.com/couchbase/couchbase-lite-java-core/issues/117
 */
public class ContextInTempDirectory extends JavaContext {
    protected final File baseDirectory;

    public ContextInTempDirectory(Context androidContext) {
        try {
            // THIS IS NOT SAFE FOR PRODUCTION USE! This can lead to race conditions. But for testing it's fine.
            String uniqueFileName =
                    File.createTempFile("ContextInTempDirectory","").getName();
            baseDirectory = new File(androidContext.getFilesDir(), uniqueFileName);
            if (baseDirectory.mkdirs() == false)  {
                throw new RuntimeException("couldn't create baseDirectory!");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public File getRootDirectory() {
        return baseDirectory;
    }

    @Override
    public File getFilesDir() {
        return baseDirectory;
    }
}
