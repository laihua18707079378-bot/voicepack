.class public final Lme/yxp/qfun/patch/BuiltinPluginInstaller;
.super Ljava/lang/Object;
.source "BuiltinPluginInstaller.java"


# direct methods
.method private constructor <init>()V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static extract(Ljava/util/zip/ZipInputStream;Ljava/io/File;)V
    .locals 7

    :goto_0
    invoke-virtual {p0}, Ljava/util/zip/ZipInputStream;->getNextEntry()Ljava/util/zip/ZipEntry;
    move-result-object v0

    if-eqz v0, :cond_end

    invoke-virtual {v0}, Ljava/util/zip/ZipEntry;->isDirectory()Z
    move-result v1

    invoke-virtual {v0}, Ljava/util/zip/ZipEntry;->getName()Ljava/lang/String;
    move-result-object v2

    new-instance v3, Ljava/io/File;
    invoke-direct {v3, p1, v2}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V

    if-eqz v1, :cond_file

    invoke-virtual {v3}, Ljava/io/File;->mkdirs()Z

    goto :goto_0

    :cond_file
    invoke-virtual {v3}, Ljava/io/File;->getParentFile()Ljava/io/File;
    move-result-object v1

    if-eqz v1, :cond_write

    invoke-virtual {v1}, Ljava/io/File;->mkdirs()Z

    :cond_write
    new-instance v1, Ljava/io/FileOutputStream;
    invoke-direct {v1, v3}, Ljava/io/FileOutputStream;-><init>(Ljava/io/File;)V

    const/16 v2, 0x1000
    new-array v2, v2, [B

    :goto_copy
    invoke-virtual {p0, v2}, Ljava/util/zip/ZipInputStream;->read([B)I
    move-result v4

    if-lez v4, :cond_close

    const/4 v5, 0x0
    invoke-virtual {v1, v2, v5, v4}, Ljava/io/FileOutputStream;->write([BII)V

    goto :goto_copy

    :cond_close
    invoke-virtual {v1}, Ljava/io/FileOutputStream;->close()V

    goto :goto_0

    :cond_end
    return-void
.end method

.method public static install(Landroid/content/Context;)V
    .locals 8

    const-string v0, "QFunBuiltin: install start"

    invoke-static {v0}, Lme/yxp/qfun/patch/BuiltinPluginInstaller;->log(Ljava/lang/String;)V

    :try_start_0
    sget-object v0, Lme/yxp/qfun/utils/qq/QQCurrentEnv;->INSTANCE:Lme/yxp/qfun/utils/qq/QQCurrentEnv;

    invoke-virtual {v0}, Lme/yxp/qfun/utils/qq/QQCurrentEnv;->getCurrentDir()Ljava/lang/String;
    move-result-object v0

    if-nez v0, :cond_cloud

    new-instance v1, Ljava/io/File;
    invoke-direct {v1, v0}, Ljava/io/File;-><init>(Ljava/lang/String;)V

    new-instance v2, Ljava/io/File;
    const-string v3, "plugin"
    invoke-direct {v2, v1, v3}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V

    invoke-virtual {v2}, Ljava/io/File;->mkdirs()Z

    new-instance v3, Ljava/io/File;
    const-string v4, "voicebox/main.java"
    invoke-direct {v3, v2, v4}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V

    invoke-virtual {v3}, Ljava/io/File;->length()J
    move-result-wide v4

    const-wide/16 v6, 0x3e8
    cmp-long v4, v4, v6

    if-lez v4, :cond_extract

    goto :cond_cloud

    :cond_extract
    if-eqz p0, :cond_cloud

    const-string v4, "me.yxp.qfun"
    const/4 v5, 0x2
    invoke-virtual {p0, v4, v5}, Landroid/content/Context;->createPackageContext(Ljava/lang/String;I)Landroid/content/Context;
    move-result-object v4

    invoke-virtual {v4}, Landroid/content/Context;->getAssets()Landroid/content/res/AssetManager;
    move-result-object v4

    const-string v5, "builtin/voicebox.zip"
    invoke-virtual {v4, v5}, Landroid/content/res/AssetManager;->open(Ljava/lang/String;)Ljava/io/InputStream;
    move-result-object v4

    new-instance v5, Ljava/util/zip/ZipInputStream;
    invoke-direct {v5, v4}, Ljava/util/zip/ZipInputStream;-><init>(Ljava/io/InputStream;)V

    invoke-static {v5, v2}, Lme/yxp/qfun/patch/BuiltinPluginInstaller;->extract(Ljava/util/zip/ZipInputStream;Ljava/io/File;)V

    invoke-virtual {v5}, Ljava/util/zip/ZipInputStream;->close()V

    invoke-virtual {v2}, Ljava/io/File;->getAbsolutePath()Ljava/lang/String;
    move-result-object v4

    new-instance v5, Ljava/lang/StringBuilder;
    invoke-direct {v5}, Ljava/lang/StringBuilder;-><init>()V
    const-string v6, "QFunBuiltin: extracted to "
    invoke-virtual {v5, v6}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v5, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v5}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v4

    invoke-static {v4}, Lme/yxp/qfun/patch/BuiltinPluginInstaller;->log(Ljava/lang/String;)V

    sget-object v4, Lme/yxp/qfun/plugin/loader/PluginManager;->INSTANCE:Lme/yxp/qfun/plugin/loader/PluginManager;

    invoke-virtual {v4}, Lme/yxp/qfun/plugin/loader/PluginManager;->getAutoLoadList()Ljava/util/List;
    move-result-object v5

    const-string v6, "voicebox"

    invoke-interface {v5, v6}, Ljava/util/List;->contains(Ljava/lang/Object;)Z
    move-result v7

    if-eqz v7, :cond_cloud

    invoke-interface {v5, v6}, Ljava/util/List;->add(Ljava/lang/Object;)Z

    invoke-virtual {v4}, Lme/yxp/qfun/plugin/loader/PluginManager;->saveConfig()V

    :cond_cloud
    invoke-static {}, Lme/yxp/qfun/patch/VoiceBoxCloud;->sync()V
    :try_end_0
    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_0

    return-void

    :catch_0
    move-exception v0

    new-instance v1, Ljava/lang/StringBuilder;
    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V
    const-string v2, "QFunBuiltin: error "
    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0}, Ljava/lang/Throwable;->toString()Ljava/lang/String;
    move-result-object v0
    invoke-virtual {v1, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v0

    invoke-static {v0}, Lme/yxp/qfun/patch/BuiltinPluginInstaller;->log(Ljava/lang/String;)V

    return-void
.end method

.method public static log(Ljava/lang/String;)V
    .locals 2

    :try_start_0
    const-string v0, "QFun"

    invoke-static {v0, p0}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
    :try_end_0
    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_0

    :catch_0
    return-void
.end method
