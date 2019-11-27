package io.renren.modules.test.jmeter.engine;

import io.renren.modules.test.entity.StressTestFileEntity;
import io.renren.modules.test.jmeter.JmeterTestPlan;
import org.apache.jmeter.JMeter;
import org.apache.jmeter.engine.*;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.testbeans.TestBean;
import org.apache.jmeter.testbeans.TestBeanHelper;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.threads.*;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.apache.jorphan.collections.SearchByClass;
import org.apache.jorphan.util.JMeterStopTestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 本身从Jmeter源码StandardJMeterEngine复制来的，为的是解决平台同时进行多脚本问题。
 * 增加了对JmeterTestPlan的应用和处理。
 *
 * 父类StandardJMeterEngine中都是私有变量，无法仅仅复写configure()方法，所以都复制过来了
 */
public class LocalStandardJMeterEngine extends StandardJMeterEngine {
    private static final Logger log = LoggerFactory.getLogger(LocalStandardJMeterEngine.class);

    // Should we exit at end of the test? (only applies to server, because host is non-null)
    // 我们应该在测试结束时退出吗？（仅适用于服务器，因为主机为非空）
    private static final boolean EXIT_AFTER_TEST =
            JMeterUtils.getPropDefault("server.exitaftertest",false);   //$NON-NLS-1$

    // Allow engine and threads to be stopped from outside a thread //允许从线程外部停止引擎和线程
    // e.g. from beanshell server   //e.g.来自beanshell服务器
    // Assumes that there is only one instance of the engine    //假设引擎只有一个实例
    // at any one time so it is not guaranteed to work ...  //任何时候，因此不能保证工作…
    private static volatile LocalStandardJMeterEngine engine;

    /*
     * Allow functions etc to register for testStopped notification. 允许函数etc注册teststopped通知
     * Only used by the function parser so far. 到目前为止只被函数解析器使用。
     * The list is merged with the testListeners and then cleared.  该列表与testlisteners合并，然后被清除
     */
    private static final List<TestStateListener> testList = new ArrayList<>();

    /** Whether to call System.exit(0) in exit after stopping RMI
     * 是否在停止RMI后调用System.exit(0)
     */
    private static final boolean REMOTE_SYSTEM_EXIT = JMeterUtils.getPropDefault("jmeterengine.remote.system.exit",false);

    /** Whether to call System.exit(1) if threads won't stop
     *  如果线程不能停止，是否调用System.exit(1)
     */
    private static final boolean SYSTEM_EXIT_ON_STOP_FAIL = JMeterUtils.getPropDefault("jmeterengine.stopfail.system.exit",true);

    /** Whether to call System.exit(0) unconditionally at end of non-GUI test
     *  是否在非GUI测试结束时无条件地调用System.exit(0)
     * */
    private static final boolean SYSTEM_EXIT_FORCED = JMeterUtils.getPropDefault("jmeterengine.force.system.exit",false);

    /** Flag to show whether test is running. Set to false to stop creating more threads.
     *  显示测试是否正在运行的标志。设置为false以停止创建更多线程
     * */
    private volatile boolean running = false;

    /** Flag to show whether engine is active. Set to false at end of test.
     *  显示引擎是否处于活动状态的标志。测试结束时设置为false
     * */
    private volatile boolean active = false;

    /** Thread Groups run sequentially 线程组按顺序运行*/
    private volatile boolean serialized = false;

    /** tearDown Thread Groups run after shutdown of main threads
     * 在关闭主线程后运行拆分线程组 */
    private volatile boolean tearDownOnShutdown = false;

    private HashTree test;

    private final String host;

    // The list of current thread groups; may be setUp, main, or tearDown.
    // 当前线程组的列表；可以是setup、main或teardown
    private final List<AbstractThreadGroup> groups = new CopyOnWriteArrayList<>();

    // 为本地增加的脚本对象。
    private StressTestFileEntity stressTestFile;

    public LocalStandardJMeterEngine() {
        this("");
    }

    public LocalStandardJMeterEngine(StressTestFileEntity stressTestFile) {
        this("");
        this.stressTestFile = stressTestFile;
    }

    public LocalStandardJMeterEngine(String host) {
        //为保留源码方式，Null可能作为判断的条件所以不变。
        this.host = "".equals(host) ? null : host;
        //Hack to allow external control
        initSingletonEngine(this);
    }

    /**
     * Set the shared engine  设置共享引擎
     */
    private static void initSingletonEngine(LocalStandardJMeterEngine localStandardJMeterEngine) {
        LocalStandardJMeterEngine.engine = localStandardJMeterEngine;
    }

    /**
     * set the shared engine to null  将共享引擎设置为null
     */
    private static void resetSingletonEngine() {
        LocalStandardJMeterEngine.engine = null;
    }

    public static void stopEngineNow() {
        if (engine != null) { //May be null if called from Unit test 如果从单元测试调用，则可能为空
            engine.stopTest(true);
        }
    }

    public static void stopEngine() {
        if (engine != null) { //May be null if called from Unit test 如果从单元测试调用，则可能为空
            engine.stopTest(false);
        }
    }

    public static synchronized void register(TestStateListener tl) {
        testList.add(tl);
    }

    public static boolean stopThread(String threadName) {
        return stopThread(threadName, false);
    }

    public static boolean stopThreadNow(String threadName) {
        return stopThread(threadName,true);
    }

    private static boolean stopThread(String threadName, boolean now) {
        if (engine == null) {
            return false; //e.g. not yet started
        }
        boolean wasStopped = false;
        //ConcurrentHashMap does not need synch. here
        for (AbstractThreadGroup threadGroup : engine.groups) {
            wasStopped = wasStopped || threadGroup.stopThread(threadName,now);
        }
        return wasStopped;
    }

    // End of code to allow engine to be controlled remotely
    // 允许远程控制engine的代码结束

    /**
     * 是为了修改的这个方法，将TestPlan替换成JmeterTestPlan
     * @param testTree
     */
    @Override
    public void configure(HashTree testTree) {
        //Is testplan serialised?
        SearchByClass jmeterTestPlan = new SearchByClass(JmeterTestPlan.class);
        JmeterTestPlan tpTemp = new JmeterTestPlan();
        //testPlan对应的是测试计划，每一个测试脚本之中只有一个测试计划，所以直接取第一个即可。
        //交换key值，让我们的自实现的子类jmeterTestPlan进入。
        testTree.replaceKey(testTree.keySet().toArray()[0],tpTemp);
        //traverse提供了一种方便的方法，通过实现 HashTreeTraverser 接口来遍历任何 HashTree，以便在树上执行一些操作，或者从树中提取信息
        testTree.traverse(jmeterTestPlan);
        Object[] plan = jmeterTestPlan.getSearchResults().toArray();
        if (plan.length == 0) {
            throw new RuntimeException("Could not find the TestPlan class!");
        }
        JmeterTestPlan tp = (JmeterTestPlan) plan[0];
        //设置我们平台自己的变量
        tp.setStressTestFile(stressTestFile);

        serialized = tp.isSerialized();
        tearDownOnShutdown = tp.isTearDownOnShutdown();
        active = true;
        test = testTree;
    }

    @Override
    public void runTest() throws JMeterEngineException {
        if (host != null) {
            long now = System.currentTimeMillis();
            System.out.println("Starting the test on host " + host + " @ " + new Date(now) +" (" +now+") ");//NOSONAR Intentional
        }
        try {
            Thread runningThread = new Thread(this, "LocalStandardJMeterEngine");
            runningThread.start();
        } catch (Exception err) {
            stopTest();
            throw new JMeterEngineException(err);
        }
    }

    private void removeThreadGroups(List<?> elements) {
        Iterator<?> iter = elements.iterator();
        while (iter.hasNext()) {
            Object item = iter.next();
            if (item instanceof AbstractThreadGroup) {
                iter.remove();
            } else if (!(item instanceof TestElement)) {
                iter.remove();
            }
        }
    }

    private void notifyTestListenersOfStart(SearchByClass<TestStateListener> testListeners) {
        for (TestStateListener tl : testListeners.getSearchResults()) {
            if(tl instanceof TestBean) {
                TestBeanHelper.prepare((TestElement) tl);
            }
            if (host == null) {
                tl.testStarted();
            }else {
                tl.testStarted(host);
            }
        }
    }

    private void notifyTestListenersofEnd(SearchByClass<TestStateListener> testListeners) {
        log.info("Notifying test listeneers of end of test");
        for (TestStateListener tl : testListeners.getSearchResults()) {
            try {
                if (host == null) {
                    tl.testEnded();
                } else {
                    tl.testEnded(host);
                }
            } catch (Exception e) {
                log.warn("Error encoutered during shutdown of" +tl.toString(),e);
            }
        }
        if (host != null) {
            log.info("Test has ended on host {} ",host);
            long now = System.currentTimeMillis();
            System.out.println("Finished the test on host " + host + " @ "+new Date(now)+" ("+now+"( "//NOSONAR Intentional
            +(EXIT_AFTER_TEST ?" - exit requested." : ""));
            if (EXIT_AFTER_TEST) {
                exit();
            }
        }
        active = false;
    }

    @Override
    public void reset() {
        if (running) {
            stopTest();
        }
    }

    /**
     * Stop Test Now
     */
    @Override
    public synchronized void stopTest() {
        stopTest(true);
    }

    @Override
    public synchronized void stopTest(boolean now) {
        Thread stopThread = new Thread(new LocalStandardJMeterEngine.StopTest(now));
        stopThread.start();
    }

    private class StopTest implements Runnable {
        private final boolean now;

        private StopTest(boolean b) {
            now = b;
        }

        /**
         * For each current thread group, invoke:
         * <ul>
         * <li>{@link AbstractThreadGroup#stop()} - set stop flag</li>
         * </ul>
         */
        private void stopAllThreadGroups() {
            // ConcurrentHashMap does not need synch. here
            for (AbstractThreadGroup threadGroup : groups) {
                threadGroup.stop();
            }
        }

        /**
         * For each thread group, invoke {@link AbstractThreadGroup#tellThreadsToStop()}
         */
        private void tellThreadGroupsToStop() {
            // ConcurrentHashMap does not need protecting
            for (AbstractThreadGroup threadGroup : groups) {
                threadGroup.tellThreadsToStop();
            }
        }

        /**
         * @return boolean true if all threads of all Thread Groups stopped
         */
        private boolean verifyThreadStopped() {
            boolean stoppedAll = true;
            // ConcurrentHashMap does not need synch. here
            for (AbstractThreadGroup threadGroup : groups) {
                stoppedAll = stoppedAll && threadGroup.verifyThreadsStopped();
            }
            return stoppedAll;
        }

        /**
         * @return total of active threads in all Thread Groups
         */
        private int countStillActiveThreads() {
            int reminingThreads = 0;
            for (AbstractThreadGroup threadGroup : groups) {
                reminingThreads += threadGroup.numberOfActiveThreads();
            }
            return reminingThreads;
        }

        @Override
        public void run() {
            running = false;
            resetSingletonEngine();
            if (now) {
                tellThreadGroupsToStop();
                pause(10L * countStillActiveThreads());
                boolean stopped = verifyThreadStopped();
                if (!stopped) { //we totally failed to stop the test
                    if (JMeter.isNonGUI()) {
                        // should we call test listeners? That might hang too ...
                        log.error(JMeterUtils.getResString("stopping_test_failed"));//$NON-NLS-1$
                        if (SYSTEM_EXIT_ON_STOP_FAIL) { //defaul is true
                            log.error("Exiting");
                            System.out.println("Fatal error,could not stop test,exiting");//NOSONAR Intentional
                            System.exit(1); //NOSONAR Intentional;
                        }else {
                            System.out.println("Fatal error,could not stop test");//NOSONAR Intentional
                        }
                    }else {
                        JMeterUtils.reportErrorToUser(
                                JMeterUtils.getResString("stopping_test_failed"),//$NON-NLS-1$
                                JMeterUtils.getResString("stopping_test_title"));//$NON-NLS-1$
                    }
                }// else will be done by threadFinished()
            } else {
                stopAllThreadGroups();
            }
        }
    }

    @Override
    public void run() {
        log.info("Running the test!");
        running = false;

        /*
         * Ensure that the sample variables are correctly initialised for each run.
         * 确保每次运行的样本变量都正确初始化。
         */
        SampleEvent.initSampleVariables();

        JMeterContextService.startTest();
        try {
            PreCompiler compiler = new PreCompiler();
            test.traverse(compiler);
        }catch (RuntimeException e) {
            log.error("Error occurred compiling the tree:",e);
            JMeterUtils.reportErrorToUser("Error occurred compiling the tree: - see log file",e);
            return; // no point continuing
        }
        /**
         * Notification of test listeners needs to happen after function 函数之后需要通知测试侦听器
         * replacement, but before setting RunningVersion to true. 替换，但在将runningversion设置为true之前。
         */
        SearchByClass<TestStateListener> testListeners = new SearchByClass<>(TestStateListener.class);//TL - S&E
        test.traverse(testListeners);

        // Merge in any additional test listeners  合并到任何其他测试侦听器中
        // currently only used by the function parser 当前仅由函数分析器使用
        testListeners.getSearchResults().addAll(testList);
        testList.clear(); // no longer needed

        //TurnElementsOn为所有匹配的节点调用{@link TestElement#setRunningVersion(boolean) setRunningVersion(true)}
        test.traverse(new TurnElementsOn());
        notifyTestListenersOfStart(testListeners);

        List<?> testLevelElements = new LinkedList<>(test.list(test.getArray()[0]));
        removeThreadGroups(testLevelElements);

        /**
         * SetupThreadGroup是一种特殊类型的线程组，可以用于在稍后执行大部分测试之前设置测试
         * AbstractThreadGroup保存jmeter线程组的设置。此类是线程安全的
         * PostThreadGroup是一种特殊类型的线程组，可用于在清理等测试结束时执行操作
         */
        SearchByClass<SetupThreadGroup> setupSearcher = new SearchByClass<>(SetupThreadGroup.class);
        SearchByClass<AbstractThreadGroup> searcher = new SearchByClass<>(AbstractThreadGroup.class);
        SearchByClass<PostThreadGroup> postSearcher = new SearchByClass<>(PostThreadGroup.class);

        test.traverse(setupSearcher);
        test.traverse(searcher);
        test.traverse(postSearcher);

        TestCompiler.initialize();
        // for each thread group, generate threads 对于每个线程组，生成线程
        // hand each thread the sampler controller 将每个线程交给采样器控制器
        // and the listeners, and the timer  还有侦听器和计时器
        Iterator<SetupThreadGroup> setupIter = setupSearcher.getSearchResults().iterator();
        Iterator<AbstractThreadGroup> iter = searcher.getSearchResults().iterator();
        Iterator<PostThreadGroup> postIter = postSearcher.getSearchResults().iterator();

        ListenerNotifier notifier = new ListenerNotifier();

        int groupCount = 0;
        /**Set total threads to zero; also clears started and finished counts
         * 将总线程数设置为零；同时清除开始计数和完成计数
         */
        JMeterContextService.clearTotalThreads();

        if (setupIter.hasNext()) {
            log.info("Starting setUp thread groups");
            while (running && setupIter.hasNext()) { //for each setup thread group
                AbstractThreadGroup group = setupIter.next();
                groupCount++;
                String groupName = group.getName();
                log.info("Starting setUp ThreadGroup:{} :{}",groupCount,groupName);
                startThreadGroup(group,groupCount,setupSearcher,testLevelElements,notifier);
                if (serialized && setupIter.hasNext()) {
                    log.info("Waiting for setup thread group:{} to finish before starting next setup group",
                            groupName);
                    group.waitThreadsStopped();
                }
            }
            log.info("Waiting for all setup thread groups to exit");
            //wait for all Setup Threads To Exit
            waitThreadStopped();
            log.info("All Setup Threads have ended");
            groupCount=0;
            JMeterContextService.clearTotalThreads();
        }

        groups.clear(); //The groups have all completed now

        /*
         * Here's where the test really starts. Run a Full GC now: it's no harm
         * at all (just delays test start by a tiny amount) and hitting one too
         * early in the test can impair results for short tests.
         * 这是测试真正开始的地方。立即运行完整的GC：
         * 这一点都没有坏处（只是稍微推迟了测试的开始时间）
         * 而在测试中过早地命中一个可能会损害短期测试的结果
         */
        JMeterUtils.helpGC();

        //JMeterContextService.getContext()提供对当前线程上下文的访问权限
        //setSamplingStarted(true)表示由jmeter内部调用，不要直接调用它(true:标记采样是否已开始)
        JMeterContextService.getContext().setSamplingStarted(true);
        boolean mainGroups = running;//still running at this point,i.e. setUp was not cancelled
        while (running && iter.hasNext()) {//for each thread group
            AbstractThreadGroup group = iter.next();
            //ignore Setup and Post here. We could have filtered the searcher.忽略设置并在此处发布。我们可以过滤搜索者。
            //but then future Thread Group objects wouldn't execute.但以后的线程组对象将不会执行
            if (group instanceof SetupThreadGroup || group instanceof PostThreadGroup) {
                continue;
            }
            groupCount++;
            String groupName = group.getName();
            log.info("Starting ThreadGroup:{}:{}",groupCount,groupName);
            startThreadGroup(group,groupCount,searcher,testLevelElements,notifier);
            if (serialized && iter.hasNext()) {
                log.info("Waiting for thread group:{} to finish before starting next group",groupName);
                group.waitThreadsStopped();
            }
        }//end of thread groups
        if (groupCount == 0) {//No TGs found
            log.info("No enabled thread groups found");
        }else {
            if (running) {
                log.info("All thread groups have been started");
            }else {
                log.info("Test stopped - no more thread groups will be started");
            }
        }

        //wait for all Test Threads To Exit
        waitThreadStopped();
        groups.clear();//The groups have all completed now

        if (postIter.hasNext()) {
            groupCount = 0;
            JMeterContextService.clearTotalThreads();
            log.info("Starting tearDown thread groups");
            if (mainGroups && !running) {//i.e. shutdown/stoppd during main thread groups
                running = tearDownOnShutdown;//re-enable for tearDown if necessary 必要时重新启用tearDown
            }
            while (running && postIter.hasNext()) {//for each setup thread group
                AbstractThreadGroup group = postIter.next();
                groupCount++;
                String groupName = group.getName();
                log.info("Starting tearDown ThreadGroup:{}:{}",groupCount,groupName);
                if (serialized && postIter.hasNext()) {
                    log.info("Waiting for post thread group:{} to finish before starting next post group",groupName);
                    group.waitThreadsStopped();
                }
            }
            waitThreadStopped();//wait for Post threads to stop
        }

        notifyTestListenersofEnd(testListeners);
        JMeterContextService.endTest();
        if (JMeter.isNonGUI() && SYSTEM_EXIT_FORCED) {
            log.info("Forced JVM shutdown requested at end of test");
            System.exit(0);// NOSONAR Intentional
        }
    }

    //启动线程组
    private void startThreadGroup(AbstractThreadGroup group,int groupCount,SearchByClass<?> searcher,List<?> testLevelElements,ListenerNotifier notifier) {
        try {
            int numThreads = group.getNumThreads();
            JMeterContextService.addTotalThreads(numThreads);
            boolean onErrorStopTest = group.getOnErrorStopTest();//检查采样器错误是否会导致测试停止
            boolean onErrorStopTestNow = group.getOnErrorStopTestNow();//检查采样器错误是否会导致测试立即停止
            boolean onErrorStopThread = group.getOnErrorStopThread();//检查采样错误是否会导致线程停止
            boolean onErrorStartNextLoop = group.getOnErrorStartNextLoop();//检查采样器错误是否会导致线程启动下一个循环
            String groupName = group.getName();
            log.info("Starting {} threads for group {}.", numThreads, groupName);
            if (onErrorStopTest) {
                log.info("Test will stop on error");
            } else if (onErrorStopTestNow) {
                log.info("Test will stop abruptly on error");
            } else if (onErrorStopThread) {
                log.info("Thread will stop on error");
            } else if (onErrorStartNextLoop) {
                log.info("Thread will start next loop on error");
            } else {
                log.info("Thread will continue on error");
            }
            ListedHashTree threadGroupTree = (ListedHashTree) searcher.getSubTree(group);
            threadGroupTree.add(group, testLevelElements);

            groups.add(group);
            group.start(groupCount, notifier, threadGroupTree, this);
        } catch (JMeterStopTestException ex) { //NOSONAR Reported by log
            JMeterUtils.reportErrorToUser("Error occurred starting thread group:" + group.getName()+
                    ", error message:" + ex.getMessage() + ", \r\nsee log file for more details",ex);
            return;//no point continuing
        }
    }

    /**
     * Wait for Group Threads to stop
     */
    private void waitThreadStopped() {
        //ConcurrentHashMap does not need synch. here
        for (AbstractThreadGroup threadGroup : groups) {
            threadGroup.waitThreadsStopped();
        }
    }

    /**
     * Clean shutdown ie,wait for end of current running samplers
     */
    public void askThreadsToStop() { //Will be null if StopTest thread has started
        if (engine != null) {
            engine.stopTest(false);
        }
    }

    /**
     * Remote exit
     * Called by RemoteJMeterEngineImpl.rexit()
     * and by notifyTestListenersOfEnd() iff exitAfterTest is true;
     * in turn that is called by the run() method and the StopTest class
     * also called
     */
    @Override
    public void exit() {
        ClientJMeterEngine.tidyRMI(log); // This should be enough to allow server to exit.
        if (REMOTE_SYSTEM_EXIT) { //default is false
            log.warn("About to run System.exit(0) on {}",host);
            //Needs to be run in a separate thread to allow RMI call to return OK
            Thread t = new Thread() {
                @Override
                public void run() {
                    pause(1000);// Allow RMI to complete
                    log.info("Bye from {}",host);
                    System.out.println("Bye from "+host);//NOSONAR Intentional
                    System.exit(0);//NOSONAR Intentional
                }
            };
            t.start();
        }
    }

    private void pause(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void setProperties(Properties p) {
        log.info("Applying properties {}",p);
        JMeterUtils.getJMeterProperties().putAll(p);
    }

    @Override
    public boolean isActive() {
        return active;
    }
    public List<AbstractThreadGroup> getGroups() {
        return groups;
    }
}
