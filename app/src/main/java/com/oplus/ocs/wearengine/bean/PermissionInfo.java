package com.oplus.ocs.wearengine.bean;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * 与官方 com.oplus.ocs.wearengine.bean.PermissionInfo 同名镜像。
 * 序列化顺序严格一致：group, groupLabel, permission, permissionLabel, isAuth。
 * Bundle 传输时接收端（health 进程）用它自己的类加载器解析，字段顺序匹配即可。
 */
public class PermissionInfo implements Parcelable {
    public String group;
    public String groupLabel;
    public String permission;
    public String permissionLabel;
    public byte isAuth;

    public PermissionInfo(String group, String groupLabel, String permission, String permissionLabel, byte isAuth) {
        this.group = group;
        this.groupLabel = groupLabel;
        this.permission = permission;
        this.permissionLabel = permissionLabel;
        this.isAuth = isAuth;
    }

    protected PermissionInfo(Parcel in) {
        group = in.readString();
        groupLabel = in.readString();
        permission = in.readString();
        permissionLabel = in.readString();
        isAuth = in.readByte();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(group);
        dest.writeString(groupLabel);
        dest.writeString(permission);
        dest.writeString(permissionLabel);
        dest.writeByte(isAuth);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<PermissionInfo> CREATOR = new Creator<PermissionInfo>() {
        @Override
        public PermissionInfo createFromParcel(Parcel in) { return new PermissionInfo(in); }
        @Override
        public PermissionInfo[] newArray(int size) { return new PermissionInfo[size]; }
    };
}
