package io.github.vstory.hook.idm.plus;

import static android.util.Log.DEBUG;
import static android.util.Log.INFO;

import android.webkit.ValueCallback;
import android.webkit.WebView;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * api102 模块入口（java_init.list 声明）。
 *
 * <p>目标 APP：1DM+ (idm.internet.download.manager.plus) 18.2 (30249)
 * <p>功能：绕过嗅探/下载中的站点名单限制（YouTube + Facebook/Instagram/fb.watch 全放行）。
 *
 * <p>原理：1DM+ 内置 Lightning 浏览器嗅探流程统一通过
 * {@code Li/UZ;->ۥۚۧۗ(String, AtomicBoolean)Z} 判定当前 URL 是否属于受限站点：
 * <ul>
 *   <li>youtube.com/.youtube.com/youtu.be → AtomicBoolean.set(true) → return true</li>
 *   <li>facebook.com/.facebook.com/instagram.com/.instagram.com/fb.watch → return true</li>
 *   <li>其它 → return false（正常流程）</li>
 * </ul>
 * 所有调用点（showDownloadDialogList / openGrabber / JSInterface.Extract / loadResource /
 * onPageFinished / handleM3u8Url …）都把 true 当作“受限/跳过/拒绝”。
 * 因此 hook 该方法：不执行原方法、直接 return false，即可解除全部名单限制。
 *
 * <p>日志分层（DEBUG 分支必须写在调用点 if (BuildConfig.DEBUG) 内，release 编译时
 * BuildConfig.DEBUG=false 为编译期常量 → 分支字节码与字符串常量整体不进入 dex）：
 * <ul>
 *   <li>INFO（release 也输出）：模块加载 / hook 安装成功或失败汇总</li>
 *   <li>DEBUG（仅 debug 构建）：hook 类/方法匹配细节 + 每次拦截调用的 url 与返回值</li>
 * </ul>
 */
public class MainHook extends XposedModule {

    public static final String TAG = "IDMPlusSnifferBypass";

    private static int sHookOk;
    private static int sHookFail;
    private static StringBuilder sHookDetail;

    public MainHook() {
        super();
    }

    // ===== 生命周期 =====

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(INFO, TAG, "api102 module loaded");
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        ClassLoader cl = param.getClassLoader();
        log(INFO, TAG, "[pkg] onPackageReady, classLoader=" + (cl != null ? "non-null" : "NULL!"));
        installHooks(cl);
    }

    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        // 允许热重载；旧 hook handle 由框架自动 unhook
        return true;
    }

    // ===== 入口：Hook 站点名单判定 =====

    private void installHooks(ClassLoader cl) {
        sHookOk = 0;
        sHookFail = 0;
        sHookDetail = new StringBuilder();

        if (BuildConfig.DEBUG) {
            log(DEBUG, TAG, "installHooks start, classLoader=" + cl);
        }

        // 1DM+ 混淆类名可能是 "i.UZ" 或 "Li/UZ;" 两种形式，逐一尝试加载
        String[] candidates = {"i.UZ", "Li/UZ;"};
        Class<?> cls = null;
        String hitName = null;
        for (String c : candidates) {
            if (BuildConfig.DEBUG) {
                log(DEBUG, TAG, "loadClass try: " + c);
            }
            try {
                cls = cl.loadClass(c);
                hitName = c;
                if (BuildConfig.DEBUG) {
                    log(DEBUG, TAG, "loadClass OK: " + c);
                }
                break;
            } catch (Throwable e) {
                if (BuildConfig.DEBUG) {
                    log(DEBUG, TAG, "loadClass FAIL: " + c + " -> " + e);
                }
            }
        }
        if (cls == null) {
            recordFail("Li/UZ;", "loadClass", "class not found (tried i.UZ / Li/UZ;)");
            finishInstall();
            return;
        }

        // 精确匹配方法 ۥۚۧۗ(String, AtomicBoolean)Z
        String methodName = "\u06E5\u06DA\u06E7\u06D7"; // ۥۚۧۗ
        Method target = null;
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.getName().equals(methodName)) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length != 2) continue;
            if (pt[0] != String.class) continue;
            if (pt[1] != AtomicBoolean.class) continue;
            if (m.getReturnType() != boolean.class) continue;
            target = m;
            if (BuildConfig.DEBUG) {
                log(DEBUG, TAG, "found target: " + m);
            }
            break;
        }
        if (target == null) {
            recordFail(hitName, methodName, "method not found (String, AtomicBoolean)Z");
            finishInstall();
            return;
        }

        try {
            target.setAccessible(true);
            hook(target).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (BuildConfig.DEBUG) {
                        Object arg0 = null;
                        try {
                            arg0 = chain.getArg(0);
                        } catch (Throwable ignored) {
                            // 拿不到参数不影响拦截
                        }
                        log(DEBUG, TAG, "ۥۚۧۗ intercepted | url=" + arg0
                                + " | return FALSE (bypass site restriction)");
                    }
                    // 绕过原方法：不把受限站点标记为 true，直接返回 false → 全部放行
                    return Boolean.FALSE;
                }
            });
            sHookOk++;
            sHookDetail.append("\n[OK] ").append(hitName).append("#").append(methodName)
                    .append("(String, AtomicBoolean)Z -> return false");
            if (BuildConfig.DEBUG) {
                log(DEBUG, TAG, "hook installed OK: " + hitName + "#" + methodName);
            }
        } catch (Throwable e) {
            recordFail(hitName, methodName, String.valueOf(e.getMessage()));
            if (BuildConfig.DEBUG) {
                log(DEBUG, TAG, "hook install threw: " + e);
            }
        }

        // B1-甲2：hook Sniffer 入口（openGrabber + showDownloadDialogList）——
        // YT 视频页注入流解析下载（原嗅探照跑叠加）
        hookSnifferEntries(cl);

        finishInstall();
    }

    // ===== B1-甲2：YT 视频流注入（1DM+ 无 YT 解析器，需自己从页面取流地址） =====
    // 设计：用户点 Sniffer（openGrabber 或 showDownloadDialogList）→ 原嗅探 proceed 照跑 →
    // 若当前是 YT watch 页，用 WebView.evaluateJavascript 读 ytInitialPlayerResponse，
    // 解析出流 URL 交 1DM+ 下载入口（先单一直链验证链路）。

    /** 注入到 YT 页的诊断+取流 JS（返回 JSON 字符串：{"diag":{...},"urls":[{url,mime,label}],err?}）。
     *  2026-09 确认：web/mweb 页面 player_response 的 formats/adaptiveFormats 全为 signatureCipher
     *  （无 manifest、无明文 url）。本版 JS 在页面内完成 yt-dlp 式解密：
     *  ① 收集所有 signatureCipher → 解析 url+s+sp；② 从 ytcfg 拿 PLAYER_JS_URL 并 fetch；
     *  ③ 正则识别 reverse/splice/swap 变换与主解密链；④ 解 s 拼完整直链。
     *  返回 Promise（evaluateJavascript 需支持 Promise 值）。 */
    private static final String YT_EXTRACT_JS =
            "(function(){"
            + "(function(){"
            + "  try {"
            + "    function qs(s){var o={};s.split('&').forEach(function(p){var i=p.indexOf('=');"
            + "      if(i>0)o[decodeURIComponent(p.slice(0,i))]=decodeURIComponent(p.slice(i+1));});return o;}"
            + "    var diag={};"
            + "    var pr=null;"
            + "    try{if(window.ytInitialPlayerResponse)pr=window.ytInitialPlayerResponse;}catch(e){}"
            + "    if(!pr){try{var c=window.ytplayer&&window.ytplayer.config;"
            + "      var a=c&&c.args&&c.args.player_response;if(typeof a==='string')pr=JSON.parse(a);}catch(e){}}"
            + "    diag.hasPr=!!pr;"
            + "    if(!pr)return JSON.stringify({diag:diag,urls:[],err:'no player response'});"
            + "    var sd=pr.streamingData||{};"
            + "    diag.hasDash=!!sd.dashManifestUrl;diag.hasHls=!!sd.hlsManifestUrl;"
            + "    var fm=(sd.formats||[]).concat(sd.adaptiveFormats||[]);"
            + "    diag.formatCount=fm.length;"
            + "    var ciphers=[];"
            + "    for(var i=0;i<fm.length;i++){var f=fm[i];var c2=f.signatureCipher||f.cipher;"
            + "      if(c2)ciphers.push({c:c2,mime:f.mimeType||'',label:(f.qualityLabel||f.quality||f.audioQuality||String(f.itag||'')),itag:f.itag});}"
            + "    diag.cipherCount=ciphers.length;"
            + "    var wants=[];"
            + "    for(var j=0;j<ciphers.length;j++){var q=qs(ciphers[j].c);"
            + "      if(q.url&&q.s)wants.push({url:q.url,s:q.s,sp:q.sp||'sig',mime:ciphers[j].mime,label:ciphers[j].label,itag:ciphers[j].itag});}"
            + "    diag.wantCount=wants.length;"
            + "    var pjs=null;"
            + "    try{pjs=window.ytcfg&&window.ytcfg.data_&&window.ytcfg.data_.PLAYER_JS_URL;}catch(e){}"
            + "    if(!pjs){try{pjs=window.ytplayer&&window.ytplayer.config&&window.ytplayer.config.args&&window.ytplayer.config.args.jsUrl;}catch(e){}}"
            + "    diag.pjs=!!pjs;"
            + "    if(!pjs||!wants.length)return JSON.stringify({diag:diag,urls:[],err:(!pjs?'no player js url':(!wants.length?'no cipher wants':''))});"
            + "    return fetch(pjs).then(function(r){if(!r.ok)throw new Error('fetch '+r.status);return r.text();}).then(function(code){"
            + "      var rev=[],spl=[],swp=[];"
            + "      var m;"
            + "      var re1=/([\\w$]+):function\\(a\\)\\{a\\.reverse\\(\\)\\}/g;"
            + "      while((m=re1.exec(code))!==null)rev.push(m[1]);"
            + "      var re2=/([\\w$]+):function\\(a,b\\)\\{a\\.splice\\(0,b\\)\\}/g;"
            + "      while((m=re2.exec(code))!==null)spl.push(m[1]);"
            + "      var re3=/([\\w$]+):function\\(a,b\\)\\{var c=a\\[0\\];a\\[0\\]=a\\[b%a\\.length\\];a\\[b(?:%a\\.length)?\\]=c\\}/g;"
            + "      while((m=re3.exec(code))!==null)swp.push(m[1]);"
            + "      diag.trans=rev.length+','+spl.length+','+swp.length;"
            + "      var kind={};"
            + "      rev.forEach(function(n){kind[n]='r';});"
            + "      spl.forEach(function(n){kind[n]='s';});"
            + "      swp.forEach(function(n){kind[n]='w';});"
            + "      var cands=[];"
            + "      var bodyRe=/function\\(a\\)\\{a=a\\.split\\(\"\"\\);(.*?)return a\\.join\\(\"\"\\)\\}/g;"
            + "      var bm;"
            + "      while((bm=bodyRe.exec(code))!==null)cands.push(bm[1]);"
            + "      var bodyRe2=/function\\s+[\\w$]+\\(a\\)\\{a=a\\.split\\(\"\"\\);(.*?)return a\\.join\\(\"\"\\)\\}/g;"
            + "      while((bm=bodyRe2.exec(code))!==null)cands.push(bm[1]);"
            + "      var plan=null;"
            + "      for(var ci=0;ci<cands.length&&!plan;ci++){"
            + "        var body=cands[ci];"
            + "        var callRe=/([\\w$]+)\\.([\\w$]+)\\(a,(\\d+)\\)|([\\w$]+)\\.([\\w$]+)\\(a\\)/g;"
            + "        var cm;var tmp=[];"
            + "        while((cm=callRe.exec(body))!==null){"
            + "          if(cm[2]&&kind[cm[2]]){tmp.push({op:kind[cm[2]],n:(cm[3]?parseInt(cm[3],10):0)});}"
            + "          else if(cm[5]&&kind[cm[5]]){tmp.push({op:kind[cm[5]],n:0});}"
            + "        }"
            + "        if(tmp.length){plan=tmp;break;}"
            + "      }"
            + "      diag.plan=plan?plan.length:0;"
            + "      function dec(sig){"
            + "        var arr=sig.split('');"
            + "        if(!plan)return sig;"
            + "        for(var k=0;k<plan.length;k++){var p=plan[k];"
            + "          if(p.op==='r')arr.reverse();"
            + "          else if(p.op==='s')arr.splice(0,p.n);"
            + "          else if(p.op==='w'){var idx=p.n%arr.length;var t=arr[0];arr[0]=arr[idx];arr[idx]=t;}}"
            + "        return arr.join('');"
            + "      }"
            + "      var urls=[];"
            + "      for(var w=0;w<wants.length;w++){var ww=wants[w];var ds=dec(ww.s);"
            + "        var u=ww.url;var sep=(u.indexOf('?')>=0?'&':'?');"
            + "        u=u+sep+encodeURIComponent(ww.sp)+'='+encodeURIComponent(ds);"
            + "        urls.push({url:u,mime:ww.mime,label:ww.label});}"
            + "      return JSON.stringify({diag:diag,urls:urls.slice(0,20)});"
            + "    }).catch(function(e){return JSON.stringify({diag:diag,urls:[],err:String(e)});});"
            + "  } catch(e){return JSON.stringify({diag:{},urls:[],err:String(e)});}"
            + "})()";


    /** B1-甲2 hook 安装：Sniffer 两个入口（openGrabber / showDownloadDialogList）。 */
    private void hookSnifferEntries(ClassLoader cl) {
        String baName = "acr.browser.lightning.activity.BrowserActivity";
        try {
            Class<?> ba = cl.loadClass(baName);

            // ① openGrabber(LightningView)
            try {
                Class<?> lv = cl.loadClass("acr.browser.lightning.view.LightningView");
                Method m = null;
                for (Method mm : ba.getDeclaredMethods()) {
                    if (mm.getName().equals("openGrabber") && mm.getParameterTypes().length == 1
                            && mm.getParameterTypes()[0] == lv) {
                        m = mm;
                        break;
                    }
                }
                if (m == null) throw new NoSuchMethodException("openGrabber(LightningView)");
                m.setAccessible(true);
                hook(m).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object ret = chain.proceed(); // 原嗅探照跑
                        try {
                            Object lvObj = chain.getArg(0);
                            if (lvObj != null) maybeInjectYtStreams(lvObj);
                        } catch (Throwable t) {
                            if (BuildConfig.DEBUG) log(DEBUG, TAG, "B1 openGrabber inject threw: " + t);
                        }
                        return ret;
                    }
                });
                sHookOk++;
                sHookDetail.append("\n[OK] ").append(baName).append("#openGrabber(LightningView) -> B1 YT inject");
            } catch (Throwable e) {
                recordFail(baName, "openGrabber", String.valueOf(e.getMessage()));
            }

            // ② showDownloadDialogList()（private 无参，Sniffer 下载面板主入口）
            try {
                Method m = null;
                for (Method mm : ba.getDeclaredMethods()) {
                    if (mm.getName().equals("showDownloadDialogList") && mm.getParameterTypes().length == 0) {
                        m = mm;
                        break;
                    }
                }
                if (m == null) throw new NoSuchMethodException("showDownloadDialogList()");
                m.setAccessible(true);
                hook(m).intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object ret = chain.proceed(); // 原嗅探面板照跑
                        try {
                            Object act = chain.getThisObject();
                            Object lv = reflectCurrentTabLightningView(act);
                            if (lv != null) maybeInjectYtStreams(lv);
                        } catch (Throwable t) {
                            if (BuildConfig.DEBUG) log(DEBUG, TAG, "B1 showDownloadDialogList inject threw: " + t);
                        }
                        return ret;
                    }
                });
                sHookOk++;
                sHookDetail.append("\n[OK] ").append(baName).append("#showDownloadDialogList() -> B1 YT inject");
            } catch (Throwable e) {
                recordFail(baName, "showDownloadDialogList", String.valueOf(e.getMessage()));
            }
        } catch (Throwable e) {
            recordFail(baName, "snifferEntries", String.valueOf(e.getMessage()));
            if (BuildConfig.DEBUG) log(DEBUG, TAG, "B1 hookSnifferEntries FAIL: " + e);
        }
    }

    /** 反射从 BrowserActivity 拿当前 tab（mTabsManager.getCurrentTab()，字段可能在父类）。 */
    private Object reflectCurrentTabLightningView(Object activity) {
        try {
            java.lang.reflect.Field f = findFieldUpHierarchy(activity.getClass(), "mTabsManager");
            if (f == null) return null;
            f.setAccessible(true);
            Object mgr = f.get(activity);
            if (mgr == null) return null;
            Method gc = null;
            for (Method mm : mgr.getClass().getMethods()) {
                if (mm.getName().equals("getCurrentTab") && mm.getParameterTypes().length == 0) {
                    gc = mm;
                    break;
                }
            }
            return gc == null ? null : gc.invoke(mgr);
        } catch (Throwable t) {
            if (BuildConfig.DEBUG) log(DEBUG, TAG, "B1 reflectCurrentTab threw: " + t);
            return null;
        }
    }

    /** 沿类继承链向上找字段（含私有）。 */
    private java.lang.reflect.Field findFieldUpHierarchy(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                return f;
            } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }

    /** 反射调用宿主 LightningView 的 getUrl/getWebView/getAppActivity/getUserAgentString。 */
    private Object invokeGetter(Object obj, String name) throws Exception {
        Method m = obj.getClass().getMethod(name);
        return m.invoke(obj);
    }

    /** 判断当前页是否 YT watch 页，是则异步读流并触发下载。 */
    private void maybeInjectYtStreams(Object lightningView) {
        // 只处理 YT watch 页
        String url = null;
        try {
            Method gu = lightningView.getClass().getMethod("getUrl");
            Object u = gu.invoke(lightningView);
            if (u instanceof String) url = (String) u;
        } catch (Throwable t) {
            if (BuildConfig.DEBUG) {
                log(DEBUG, TAG, "B1 getUrl threw: " + t);
            }
        }
        if (url == null) {
            // fallback: WebView.getUrl()
            try {
                Object wv = invokeGetter(lightningView, "getWebView");
                if (wv instanceof WebView) url = ((WebView) wv).getUrl();
            } catch (Throwable ignored) { }
        }
        if (url == null) return;
        if (BuildConfig.DEBUG) {
            log(DEBUG, TAG, "B1 openGrabber current url=" + url);
        }
        boolean ytWatch = url.startsWith("https://www.youtube.com/watch")
                || url.startsWith("https://m.youtube.com/watch")
                || url.startsWith("https://youtube.com/watch")
                || url.contains("youtube.com/watch?v=")
                || url.contains("youtube.com/shorts/")
                || url.contains("youtu.be/");
        if (!ytWatch) return;

        if (BuildConfig.DEBUG) {
            log(DEBUG, TAG, "B1 YT watch page detected, extracting streams...");
        }

        // 异步（UI 线程）读页面流
        try {
            Object wvObj = invokeGetter(lightningView, "getWebView");
            if (!(wvObj instanceof WebView)) return;
            final WebView wv = (WebView) wvObj;
            final Object lvRef = lightningView;
            wv.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        wv.evaluateJavascript(YT_EXTRACT_JS, new ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String value) {
                                handleYtStreamsResult(value, lvRef, wv);
                            }
                        });
                    } catch (Throwable t) {
                        if (BuildConfig.DEBUG) {
                            log(DEBUG, TAG, "B1 evaluateJavascript threw: " + t);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            if (BuildConfig.DEBUG) {
                log(DEBUG, TAG, "B1 getWebView threw: " + t);
            }
        }
    }

    /** 处理 YT 页面返回的诊断+流 JSON：打诊断日志；按优先级把候选 url 交 1DM+ 下载入口。 */
    private void handleYtStreamsResult(String jsResult, Object lightningView, WebView wv) {
        if (BuildConfig.DEBUG) {
            log(DEBUG, TAG, "B1 yt diag js result len=" + (jsResult == null ? "null" : jsResult.length()));
        }
        if (jsResult == null) return;
        // evaluateJavascript 对字符串返回值做了 JSON 编码（外层带引号转义）
        String json = null;
        try {
            if (jsResult.startsWith("\"")) {
                org.json.JSONObject jo = new org.json.JSONObject("{\"v\":" + jsResult + "}");
                json = jo.optString("v");
            } else {
                json = jsResult;
            }
        } catch (Throwable t) {
            if (BuildConfig.DEBUG) log(DEBUG, TAG, "B1 decode threw: " + t);
            return;
        }
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            // --- 诊断日志 ---
            org.json.JSONObject diag = root.optJSONObject("diag");
            if (diag != null && BuildConfig.DEBUG) {
                log(DEBUG, TAG, "B1 diag hasPr=" + diag.optBoolean("hasPr")
                        + " from=" + diag.optString("prFrom")
                        + " playability=" + diag.optString("playability")
                        + " reason=" + diag.optString("reason"));
                log(DEBUG, TAG, "B1 diag hasDash=" + diag.optBoolean("hasDash")
                        + " hasHls=" + diag.optBoolean("hasHls"));
                if (!diag.optString("dashUrl").isEmpty()) {
                    String d = diag.optString("dashUrl");
                    log(DEBUG, TAG, "B1 diag dashUrl=" + (d.length() > 220 ? d.substring(0, 220) + "..." : d));
                }
                if (!diag.optString("hlsUrl").isEmpty()) {
                    String h = diag.optString("hlsUrl");
                    log(DEBUG, TAG, "B1 diag hlsUrl=" + (h.length() > 220 ? h.substring(0, 220) + "..." : h));
                }
                log(DEBUG, TAG, "B1 diag formats=" + diag.optInt("formatsCount")
                        + " (url=" + diag.optInt("formatsWithUrl") + ",cipher=" + diag.optInt("formatsWithCipher") + ")"
                        + " adaptive=" + diag.optInt("adaptiveCount")
                        + " (url=" + diag.optInt("adaptiveWithUrl") + ",cipher=" + diag.optInt("adaptiveWithCipher") + ")");
                org.json.JSONObject samp = diag.optJSONObject("sample");
                if (samp != null) {
                    log(DEBUG, TAG, "B1 diag sample=" + samp.toString());
                }
            }
            if (root.has("err") && BuildConfig.DEBUG) {
                log(DEBUG, TAG, "B1 js err=" + root.optString("err"));
            }
            // --- 候选 url → 下载 ---
            org.json.JSONArray urls = root.optJSONArray("urls");
            if (urls == null || urls.length() == 0) {
                if (BuildConfig.DEBUG) log(DEBUG, TAG, "B1 no candidate url found"
                        + (root.has("err") ? " err=" + root.optString("err") : ""));
                return;
            }
            if (BuildConfig.DEBUG) {
                log(DEBUG, TAG, "B1 candidate urls=" + urls.length());
            }
            // 挑选：首个带 mime 或 dash/hls/format kind 或 url 含 itag= 的候选（解密后的直链通常带 mime+itag）
            String pickUrl = null;
            String pickLabel = null;
            for (int i = 0; i < urls.length() && pickUrl == null; i++) {
                org.json.JSONObject o = urls.optJSONObject(i);
                if (o == null) continue;
                String u = o.optString("url");
                if (u.isEmpty()) continue;
                String mime = o.optString("mime");
                String kind = o.optString("kind");
                if (mime.startsWith("video/") || mime.startsWith("audio/")
                        || kind.equals("dash") || kind.equals("hls") || kind.equals("format")
                        || u.contains("&itag=") || u.contains("?itag=")) {
                    pickUrl = u;
                    pickLabel = o.optString("label");
                    if (BuildConfig.DEBUG) {
                        log(DEBUG, TAG, "B1 pick[" + i + "] kind=" + kind + " mime=" + mime
                                + " label=" + pickLabel + " urlLen=" + u.length()
                                + " head=" + (u.length() > 180 ? u.substring(0, 180) + "..." : u));
                    }
                }
            }
            if (pickUrl == null) {
                org.json.JSONObject first = urls.optJSONObject(0);
                if (first != null && BuildConfig.DEBUG) {
                    String u = first.optString("url");
                    log(DEBUG, TAG, "B1 only suspicious urlLen=" + u.length()
                            + " head=" + (u.length() > 160 ? u.substring(0, 160) + "..." : u)
                            + " -> 无 mime/itag, 不发下载器");
                }
                return;
            }
            if (BuildConfig.DEBUG) {
                log(DEBUG, TAG, "B1 pick urlLen=" + pickUrl.length() + " label=" + pickLabel
                        + " head=" + (pickUrl.length() > 160 ? pickUrl.substring(0, 160) + "..." : pickUrl));
            }
            // 交给 1DM+ 下载入口
            Object activity = null;
            String ua = null;
            try {
                activity = invokeGetter(lightningView, "getAppActivity");
                ua = (String) invokeGetter(lightningView, "getUserAgentString");
            } catch (Throwable ignored) { }
            String ref = wv.getUrl();
            try {
                Class<?> eCls = Class.forName("idm.internet.download.manager.e", false, lightningView.getClass().getClassLoader());
                for (Method m : eCls.getDeclaredMethods()) {
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length != 7) continue;
                    if (pt[0].getName().equals("android.app.Activity")
                            && pt[1] == String.class && pt[2] == String.class && pt[3] == String.class
                            && pt[4] == boolean.class && pt[5] == String.class && pt[6] == boolean.class) {
                        if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                        m.setAccessible(true);
                        if (BuildConfig.DEBUG) {
                            log(DEBUG, TAG, "B1 invoking downloader: " + eCls.getName() + "#" + m.getName());
                        }
                        m.invoke(null, activity, pickUrl, ref, ua, Boolean.FALSE,
                                pickLabel != null && !pickLabel.isEmpty() ? pickLabel : "YouTube", Boolean.FALSE);
                        if (BuildConfig.DEBUG) {
                            log(DEBUG, TAG, "B1 downloader invoked OK");
                        }
                        return;
                    }
                }
                if (BuildConfig.DEBUG) log(DEBUG, TAG, "B1 downloader method not found");
            } catch (Throwable t) {
                if (BuildConfig.DEBUG) {
                    log(DEBUG, TAG, "B1 hand to downloader threw: " + t);
                }
            }
        } catch (Throwable t) {
            if (BuildConfig.DEBUG) log(DEBUG, TAG, "B1 parse threw: " + t);
        }
    }

    // ===== 日志工具（仅 INFO 走工具方法；DEBUG 日志必须写调用点 if(BuildConfig.DEBUG)，见类注释） =====

    private void recordFail(String cls, String m, String why) {
        sHookFail++;
        sHookDetail.append("\n[FAIL] ").append(cls).append("#").append(m).append(": ").append(why);
    }

    private void finishInstall() {
        log(INFO, TAG, "installHooks done: " + sHookOk + " OK / " + sHookFail + " FAIL" + sHookDetail);
    }
}
