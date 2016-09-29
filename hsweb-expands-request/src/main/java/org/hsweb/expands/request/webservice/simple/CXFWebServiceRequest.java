package org.hsweb.expands.request.webservice.simple;

import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
import org.apache.cxf.tools.common.CommandInterfaceUtils;
import org.apache.cxf.tools.common.ToolContext;
import org.apache.cxf.tools.common.model.JavaInterface;
import org.apache.cxf.tools.common.model.JavaServiceClass;
import org.apache.cxf.tools.wsdlto.WSDLToJava;
import org.hsweb.commons.MD5;
import org.hsweb.commons.file.FileUtils;
import org.hsweb.expands.request.webservice.WebServiceRequest;
import org.hsweb.expands.request.webservice.WebServiceRequestInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.*;
import java.io.*;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.util.*;

public class CXFWebServiceRequest implements WebServiceRequest {

    private static Logger logger = LoggerFactory.getLogger(CXFWebServiceRequest.class);

    private String CODE_DIR;
    private String SRC_DIR;
    private String BIN_DIR;
    private String url;
    private ClassLoader serviceClassLoader;
    private Map<String, Class> serviceMap = new HashMap<>();
    private Map<String, Class> interfaceMap = new HashMap<>();
    private static String classpath = "";

    public CXFWebServiceRequest(String url) throws Exception {
        CODE_DIR = System.getProperty("java.io.tmpdir") + "/org/hsweb/request/ws/" + MD5.defaultEncode(url) + "/";
        SRC_DIR = CODE_DIR + "src/";
        BIN_DIR = CODE_DIR + "bin/";
        new File(SRC_DIR).mkdirs();
        new File(BIN_DIR).mkdirs();
        serviceClassLoader = new URLClassLoader(new URL[]{new File(BIN_DIR).toURI().toURL()}, CXFWebServiceRequest.class.getClassLoader());
        this.url = url;
    }

    static {
        classpath = System.getProperty("java.class.path");
    }

    @Override
    public WebServiceRequest init() throws Exception {
        if (logger.isDebugEnabled())
            logger.debug("wsdl to java:{}->{}", url, SRC_DIR);
        CommandInterfaceUtils.commandCommonMain();
        WSDLToJava w2j = new WSDLToJava(new String[]{"-client", "-d", SRC_DIR, url});
        ToolContext toolContext = new ToolContext();
        w2j.run(toolContext);
        buildClass();
        for (JavaInterface javaInterface : toolContext.getJavaModel().getInterfaces().values()) {
            interfaceMap.put(javaInterface.getName(), serviceClassLoader.loadClass(javaInterface.getFullClassName()));
        }
        for (JavaServiceClass serviceClass : toolContext.getJavaModel().getServiceClasses().values()) {
            serviceMap.put(serviceClass.getName(), serviceClassLoader.loadClass(serviceClass.getFullClassName()));
        }
        return this;
    }

    protected void buildClass() {
        //编译
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<JavaFileObject> jfiles = new ArrayList<>();
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, Locale.CHINA, Charset.forName("UTF-8"));
        File src = new File(SRC_DIR);
        FileUtils.scanFile(src.getAbsolutePath(), true, (d, file) -> {
            if (!"package-info.java".equals(file.getName()) && file.getName().endsWith(".java")) {
                jfiles.add(new JavaClassObject(file.getAbsolutePath().split("[.]")[0], JavaFileObject.Kind.SOURCE));
            }
        });
        List<String> options = new ArrayList<>();
        options.add("-d");
        options.add(BIN_DIR);
        options.add("-cp");
        options.add(classpath);
        options.add("-encoding");
        options.add("UTF-8");
        if (logger.isDebugEnabled()) {
            logger.debug("javac {}", options.stream().reduce((s, s2) -> s + " " + s2).get());
        }
        JavaCompiler.CompilationTask task = compiler.getTask(null, fm, diagnostics, options, null, jfiles);
        boolean success = task.call();
        if (!success) {
            StringBuilder builder = new StringBuilder();
            for (Diagnostic<?> diagnostic : diagnostics.getDiagnostics()) {
                builder.append(diagnostic).append("\n");
            }
            throw new RuntimeException(builder.toString());
        }
    }

    public class JavaClassObject extends SimpleJavaFileObject {

        public JavaClassObject(String name, JavaFileObject.Kind kind) {
            super(URI.create(name + kind.extension), kind);
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            return FileUtils.reader2String(uri.getPath());
        }

        @Override
        public OutputStream openOutputStream() throws IOException {
            return new FileOutputStream(uri.getPath());
        }
    }

    @Override
    public WebServiceRequestInvoker request(String method) throws NoSuchMethodException {
        return request(interfaces().get(0), services().get(0), method);
    }

    @Override
    public WebServiceRequestInvoker request() throws NoSuchMethodException {
        return request(interfaces().get(0), services().get(0), null);
    }

    @Override
    public Method[] methods(String interfaceName) {
        Class interfaceClass = interfaceMap.get(interfaceName);
        if (interfaceClass == null) throw new NullPointerException(interfaceName);
        return interfaceClass.getMethods();
    }

    @Override
    public WebServiceRequestInvoker request(String interfaceName, String service, String method) throws NoSuchMethodException {
        Class serviceClass = serviceMap.get(service);
        if (serviceClass == null) throw new NullPointerException(service);
        Class interfaceClass = interfaceMap.get(interfaceName);
        if (interfaceClass == null) throw new NullPointerException(interfaceName);
        JaxWsProxyFactoryBean svr = new JaxWsProxyFactoryBean();
        svr.setServiceClass(interfaceClass);
        svr.setAddress(url);
        Object shareWebService = svr.create();
        Method[] methods = interfaceClass.getMethods();
        Method method1 = null;
        if (methods.length == 0 || method == null) {
            method1 = methods[0];
        } else
            for (int i = 0; i < methods.length; i++) {
                if (method.equals(methods[i].getName())) {
                    method1 = methods[i];
                    break;
                }
            }
        if (null == method1) throw new NoSuchMethodException(method);
        Method tmp = method1;
        return param -> {
            Object data = tmp.invoke(shareWebService, param);
            return new SimpleWebServiceResult(data);
        };
    }

    @Override
    public List<String> services() {
        return new ArrayList<>(serviceMap.keySet());
    }

    @Override
    public List<String> interfaces() {
        return new ArrayList<>(interfaceMap.keySet());
    }

}
