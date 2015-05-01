package org.wordpress.android.editor;

import android.content.Context;
import android.os.Build;
import android.os.Message;
import android.util.AttributeSet;
import android.webkit.WebView;

import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * <p>Compatibility <code>EditorWebView</code> for pre-Chromium WebView (API<19). Provides a custom method for executing
 * JavaScript, {@link #loadJavaScript(String)}, instead of {@link WebView#loadUrl(String)}. This is needed because
 * <code>WebView#loadUrl(String)</code> on API<19 eventually calls <code>WebViewClassic#hideSoftKeyboard()</code>,
 * hiding the keyboard whenever JavaScript is executed.</p>
 *
 * <p>This class uses reflection to access the normally inaccessible <code>WebViewCore#sendMessage(Message)</code>
 * and use it to execute JavaScript, sidestepping <code>WebView#loadUrl(String)</code> and the keyboard issue.</p>
 */
@SuppressWarnings("TryWithIdenticalCatches")
public class EditorWebViewCompatibility extends EditorWebViewAbstract {
    private static final int EXECUTE_JS = 194; // WebViewCore internal JS message code
    private static final int LOAD_DATA = 139; // WebViewCore internal JS message code

    private Object mWebViewCore;
    private Method mSendMessageMethod;
    private Method mSwitchOutDrawHistoryMethod;

    public EditorWebViewCompatibility(Context context, AttributeSet attrs) {
        super(context, attrs);
        try {
            this.initReflection();
        } catch (ReflectionException e) {
            AppLog.e(T.EDITOR, e);
            handleReflectionFailure();
        }
    }

    private void initReflection() throws ReflectionException {
        try {
            mWebViewCore = this.getWebViewCore();
            if (mWebViewCore != null) {
                // Access WebViewCore#sendMessage(Message) method
                mSendMessageMethod = mWebViewCore.getClass().getDeclaredMethod("sendMessage", Message.class);
                mSendMessageMethod.setAccessible(true);
            }

            if (Build.VERSION.SDK_INT >= 16) {
                Object provider = this.getWebViewClassicProvider();
                mSwitchOutDrawHistoryMethod = provider.getClass().getDeclaredMethod("switchOutDrawHistory");
            } else {
                mSwitchOutDrawHistoryMethod = WebView.class.getDeclaredMethod("switchOutDrawHistory");
            }
            mSwitchOutDrawHistoryMethod.setAccessible(true);

        } catch (NoSuchFieldException e) {
            throw new ReflectionException(e);
        } catch (NoSuchMethodException e) {
            throw new ReflectionException(e);
        } catch (IllegalAccessException e) {
            throw new ReflectionException(e);
        }
    }

    private void loadJavaScript(final String javaScript) throws ReflectionException {

        Message jsMessage = Message.obtain(null, EXECUTE_JS, javaScript);
        try {
            this.callSendMessage(jsMessage);
        } catch (InvocationTargetException e) {
            throw new ReflectionException(e);
        } catch (IllegalAccessException e) {
            throw new ReflectionException(e);
        }
    }

    public void execJavaScriptFromString(String javaScript) {
        try {
            loadJavaScript(javaScript);
        } catch(ReflectionException e) {
            AppLog.e(T.EDITOR, e);
            handleReflectionFailure();
        }
    }

    private void loadBaseURLWithData(final String baseUrl, final String data, final String mimeType,
                                   final String encoding, final String historyUrl) throws ReflectionException {
        try {
            this.callSwitchOutDrawHistory();
            Object baseUrlDataInstance = this.getBaseUrlDataInstance(baseUrl, data, mimeType, encoding, historyUrl);
            Message baseUrlMessage = Message.obtain(null, LOAD_DATA, baseUrlDataInstance);
            this.callSendMessage(baseUrlMessage);
        } catch (InstantiationException e) {
            throw new ReflectionException(e);
        } catch (IllegalAccessException e) {
            throw new ReflectionException(e);
        } catch (ClassNotFoundException e) {
            throw new ReflectionException(e);
        } catch (NoSuchMethodException e) {
            throw new ReflectionException(e);
        } catch (InvocationTargetException e) {
            throw new ReflectionException(e);
        } catch (NoSuchFieldException e) {
            throw new ReflectionException(e);
        }
    }

    public void execLoadDataWithBaseURL (String baseUrl, String data, String mimeType, String encoding, String historyUrl) {
        try {
            loadBaseURLWithData(baseUrl, data, mimeType, encoding, historyUrl);
        } catch(ReflectionException e) {
            AppLog.e(T.EDITOR, e);
            handleReflectionFailure();
        }
    }

    private void handleReflectionFailure() {
        // TODO: Fallback to legacy editor and pass the error to analytics
    }

    private void callSwitchOutDrawHistory() throws ReflectionException, InvocationTargetException, IllegalAccessException, NoSuchFieldException {
        if (mSwitchOutDrawHistoryMethod == null) {
            initReflection();
        } else {
            Object provider;
            if (Build.VERSION.SDK_INT >= 16) {
                provider = getWebViewClassicProvider();
            } else {
                provider = this;
            }
            mSwitchOutDrawHistoryMethod.invoke(provider);
        }
    }

    private void callSendMessage(Message messageToSend) throws ReflectionException, InvocationTargetException, IllegalAccessException {
        if (mSendMessageMethod == null) {
            initReflection();
        } else {
            mSendMessageMethod.invoke(mWebViewCore, messageToSend);
        }
    }

    public class ReflectionException extends Exception {
        public ReflectionException(Throwable cause) {
            super(cause);
        }
    }

    private Object getWebViewCore() throws IllegalStateException, IllegalAccessException, NoSuchFieldException, NoSuchMethodException {
        Object webViewCore;
        Field webViewCoreField;

        if (Build.VERSION.SDK_INT >= 16) {
            Object provider = this.getWebViewClassicProvider();
            provider = this.getWebViewClassicProvider();
            webViewCoreField = provider.getClass().getDeclaredField("mWebViewCore");
            webViewCoreField.setAccessible(true);
            webViewCore = webViewCoreField.get(provider);
        } else {
            webViewCoreField = WebView.class.getDeclaredField("mWebViewCore");
            webViewCoreField.setAccessible(true);
            webViewCore = webViewCoreField.get(this);
        }

        return webViewCore;
    }

    private Object getWebViewClassicProvider() throws NoSuchFieldException, IllegalAccessException {
        Object provider;
        Field webViewProviderField = WebView.class.getDeclaredField("mProvider");
        webViewProviderField.setAccessible(true);
        provider = webViewProviderField.get(this);
        return provider;
    }

    private final String BASE_URL_DATA_CLASS_NAME = "android.webkit.WebViewCore$BaseUrlData";
    private Object getBaseUrlDataInstance(String baseUrl, String data, String mimeType, String encoding, String historyUrl)
            throws NoSuchFieldException, IllegalAccessException, InvocationTargetException, InstantiationException, ClassNotFoundException, NoSuchMethodException {

        Class<?> baseUrlDataClass = Class.forName(BASE_URL_DATA_CLASS_NAME);
        Constructor<?> constructor = baseUrlDataClass.getDeclaredConstructor();
        constructor.setAccessible(true);

        Field mBaseUrlField = baseUrlDataClass.getDeclaredField("mBaseUrl");
        mBaseUrlField.setAccessible(true);
        Field mDataField = baseUrlDataClass.getDeclaredField("mData");
        mDataField.setAccessible(true);
        Field mMimeTypeField = baseUrlDataClass.getDeclaredField("mMimeType");
        mMimeTypeField.setAccessible(true);
        Field mEncodingField = baseUrlDataClass.getDeclaredField("mEncoding");
        mEncodingField.setAccessible(true);
        Field mHistoryUrlField = baseUrlDataClass.getDeclaredField("mHistoryUrl");
        mHistoryUrlField.setAccessible(true);

        Object baseUrlDataInstance = constructor.newInstance();
        mBaseUrlField.set(baseUrlDataInstance, baseUrl);
        mDataField.set(baseUrlDataInstance, data);
        mMimeTypeField.set(baseUrlDataInstance, mimeType);
        mEncodingField.set(baseUrlDataInstance, encoding);
        mHistoryUrlField.set(baseUrlDataInstance, historyUrl);

        return baseUrlDataInstance;
    }

}