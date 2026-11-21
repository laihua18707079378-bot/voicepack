.class public final Lme/yxp/qfun/plugin/net/LocalPluginInjector;
.super Ljava/lang/Object;
.source "LocalPluginInjector.java"


# direct methods
.method public constructor <init>()V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static insert(Ljava/util/List;)V
    .locals 11
    .annotation system Ldalvik/annotation/Signature;
        value = {
            "(",
            "Ljava/util/List<",
            "Lme/yxp/qfun/plugin/net/ScriptInfo;",
            ">;)V"
        }
    .end annotation

    :try_start_0
    new-instance v0, Lme/yxp/qfun/plugin/net/ScriptInfo;

    const-string v1, "voicebox"

    const-string v2, "\u8bed\u97f3\u76d2"

    invoke-static {}, Lme/yxp/qfun/plugin/net/LocalPluginInjector;->readVersion()Ljava/lang/String;

    move-result-object v3

    const-string v4, "nl"

    const-string v5, "\u804a\u5929\u5c0f\u60ac\u6d6e\u7a97\u8bed\u97f3\u76d2\uff1a\u4e91\u7aef\u8bed\u97f3\u5305\u0020\u002f\u0020\u7f51\u6613\u4e91\u97f3\u4e50\u5728\u7ebf\u641c\u7d22\u8bd5\u542c"

    const-string v6, "voicebox.zip"

    const-string v7, "2026-11-20"

    const-string v8, "approved"

    const/4 v9, 0x0

    const-string v10, "https://mcer.dpdns.org/plugin/voicebox.zip"

    invoke-direct/range {v0 .. v10}, Lme/yxp/qfun/plugin/net/ScriptInfo;-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V

    const/4 v1, 0x0

    :goto_0
    invoke-interface {p0}, Ljava/util/List;->size()I

    move-result v2

    if-ge v1, v2, :cond_1

    invoke-interface {p0, v1}, Ljava/util/List;->get(I)Ljava/lang/Object;

    move-result-object v2

    check-cast v2, Lme/yxp/qfun/plugin/net/ScriptInfo;

    invoke-virtual {v2}, Lme/yxp/qfun/plugin/net/ScriptInfo;->getId()Ljava/lang/String;

    move-result-object v2

    const-string v3, "voicebox"

    invoke-virtual {v3, v2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v2

    if-eqz v2, :cond_0

    invoke-interface {p0, v1}, Ljava/util/List;->remove(I)Ljava/lang/Object;

    goto :cond_1

    :cond_0
    add-int/lit8 v1, v1, 0x1

    goto :goto_0

    :cond_1
    const/4 v1, 0x0

    invoke-interface {p0, v1, v0}, Ljava/util/List;->add(ILjava/lang/Object;)V
    :try_end_0
    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_0

    return-void

    :catch_0
    move-exception v0

    return-void
.end method

# 动态读取版本：优先服务器 latest.json，失败则读本地已安装插件 info.prop，最后回退默认值
.method public static readVersion()Ljava/lang/String;
    .locals 8

    :try_start_0
    new-instance v0, Ljava/net/URL;

    const-string v1, "https://mcer.dpdns.org/plugin/latest.json"

    invoke-direct {v0, v1}, Ljava/net/URL;-><init>(Ljava/lang/String;)V

    invoke-virtual {v0}, Ljava/net/URL;->openConnection()Ljava/net/URLConnection;

    move-result-object v0

    check-cast v0, Ljava/net/HttpURLConnection;

    const/16 v1, 0x7d0

    invoke-virtual {v0, v1}, Ljava/net/HttpURLConnection;->setConnectTimeout(I)V

    invoke-virtual {v0, v1}, Ljava/net/HttpURLConnection;->setReadTimeout(I)V

    invoke-virtual {v0}, Ljava/net/HttpURLConnection;->getInputStream()Ljava/io/InputStream;

    move-result-object v1

    new-instance v2, Ljava/io/ByteArrayOutputStream;

    invoke-direct {v2}, Ljava/io/ByteArrayOutputStream;-><init>()V

    const/16 v3, 0x400

    new-array v3, v3, [B

    :goto_read
    invoke-virtual {v1, v3}, Ljava/io/InputStream;->read([B)I

    move-result v4

    if-lez v4, :read_done

    const/4 v5, 0x0

    invoke-virtual {v2, v3, v5, v4}, Ljava/io/ByteArrayOutputStream;->write([BII)V

    goto :goto_read

    :read_done
    invoke-virtual {v1}, Ljava/io/InputStream;->close()V

    invoke-virtual {v0}, Ljava/net/HttpURLConnection;->disconnect()V

    new-instance v0, Ljava/lang/String;

    invoke-virtual {v2}, Ljava/io/ByteArrayOutputStream;->toByteArray()[B

    move-result-object v1

    const-string v2, "UTF-8"

    invoke-direct {v0, v1, v2}, Ljava/lang/String;-><init>([BLjava/lang/String;)V

    new-instance v1, Lorg/json/JSONObject;

    invoke-direct {v1, v0}, Lorg/json/JSONObject;-><init>(Ljava/lang/String;)V

    const-string v2, "version"

    invoke-virtual {v1, v2}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v3

    if-eqz v3, :remote_local

    invoke-virtual {v3}, Ljava/lang/String;->length()I

    move-result v4

    if-lez v4, :remote_local

    return-object v3
    :try_end_0
    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_remote

    :catch_remote
    move-exception v0

    :remote_local
    :try_start_1
    sget-object v0, Lme/yxp/qfun/utils/qq/QQCurrentEnv;->INSTANCE:Lme/yxp/qfun/utils/qq/QQCurrentEnv;

    invoke-virtual {v0}, Lme/yxp/qfun/utils/qq/QQCurrentEnv;->getCurrentDir()Ljava/lang/String;

    move-result-object v0

    if-nez v0, :local_default

    new-instance v1, Ljava/io/File;

    invoke-direct {v1, v0}, Ljava/io/File;-><init>(Ljava/lang/String;)V

    new-instance v2, Ljava/io/File;

    const-string v3, "plugin/voicebox/info.prop"

    invoke-direct {v2, v1, v3}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V

    new-instance v3, Ljava/io/BufferedReader;

    new-instance v4, Ljava/io/FileReader;

    invoke-direct {v4, v2}, Ljava/io/FileReader;-><init>(Ljava/io/File;)V

    invoke-direct {v3, v4}, Ljava/io/BufferedReader;-><init>(Ljava/io/Reader;)V

    :goto_prop
    invoke-virtual {v3}, Ljava/io/BufferedReader;->readLine()Ljava/lang/String;

    move-result-object v4

    if-nez v4, :check_prop

    invoke-virtual {v3}, Ljava/io/BufferedReader;->close()V

    goto :local_default

    :check_prop
    const-string v5, "versionCode="

    invoke-virtual {v4, v5}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z

    move-result v6

    if-eqz v6, :next_prop

    invoke-virtual {v3}, Ljava/io/BufferedReader;->close()V

    const/16 v6, 0xc

    invoke-virtual {v4, v6}, Ljava/lang/String;->substring(I)Ljava/lang/String;

    move-result-object v6

    invoke-virtual {v6}, Ljava/lang/String;->trim()Ljava/lang/String;

    move-result-object v6

    return-object v6

    :next_prop
    goto :goto_prop

    :local_default
    const-string v0, "1.9.4"

    return-object v0
    :try_end_1
    .catch Ljava/lang/Throwable; {:try_start_1 .. :try_end_1} :catch_local

    :catch_local
    move-exception v0

    const-string v0, "1.9.4"

    return-object v0
.end method
