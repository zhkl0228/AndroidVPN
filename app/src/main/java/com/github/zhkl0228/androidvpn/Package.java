package com.github.zhkl0228.androidvpn;

import androidx.annotation.NonNull;

import java.io.DataOutput;
import java.io.IOException;

class Package {

    final String packageName;
    final CharSequence label;
    final long versionCode;

    public Package(String packageName, CharSequence label, long versionCode) {
        this.packageName = packageName;
        this.label = label;
        this.versionCode = versionCode;
    }

    @NonNull
    @Override
    public String toString() {
        return label + "(" + packageName + ")";
    }

    void output(DataOutput dataOutput) throws IOException {
        dataOutput.writeUTF(packageName);
        dataOutput.writeUTF(String.valueOf(label));
        dataOutput.writeLong(versionCode);
    }
}
