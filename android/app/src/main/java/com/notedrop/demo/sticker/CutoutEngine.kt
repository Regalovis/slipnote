// 这个文件已经废弃，可以删除。
//
// 原本是 MediaPipe deeplab_v3 的语义分割封装，用来做"自动抠图"。
// 但语义分割只认识 PASCAL VOC 的 20 类常见物体，对商品图、截图
// 这类"主体"它根本不认识 —— 用户实测"几乎无法使用"。
//
// 已换成颜色键方案，见 MagicWand.kt + CutoutView.kt + CutoutActivity.
