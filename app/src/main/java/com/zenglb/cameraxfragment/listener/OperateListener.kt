package com.zenglb.cameraxfragment.listener;

/**
 * 拍照，视频后的回调路径
 *
 */
public interface OperateListener {

    //Called when the video record is finished and saved
    fun onVideoRecorded(filePath:String);

    //called when the photo is taken and saved
    fun  onPhotoTaken(filePath:String );

}
