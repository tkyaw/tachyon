package tachyon.master;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.apache.log4j.Logger;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadedSelectorServer;
import org.apache.thrift.transport.TNonblockingServerSocket;
import org.apache.thrift.transport.TTransportException;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;

import tachyon.Constants;
import tachyon.LeaderSelectorClient;
import tachyon.UnderFileSystem;
import tachyon.Version;
import tachyon.conf.CommonConf;
import tachyon.conf.MasterConf;
import tachyon.thrift.MasterService;
import tachyon.util.CommonUtils;
import tachyon.util.NetworkUtils;
import tachyon.web.UIWebServer;

/**
 * Entry point for the Master program.
 */
public class TachyonMaster {
  private static final Logger LOG = Logger.getLogger(Constants.LOGGER_TYPE);

  public static void main(String[] args) {
    if (args.length != 0) {
      LOG.info("java -cp target/tachyon-" + Version.VERSION + "-jar-with-dependencies.jar "
          + "tachyon.Master");
      System.exit(-1);
    }
    MasterConf mConf = MasterConf.get();
    TachyonMaster master =
        new TachyonMaster(new InetSocketAddress(mConf.HOSTNAME, mConf.PORT), mConf.WEB_PORT,
            mConf.SELECTOR_THREADS, mConf.QUEUE_SIZE_PER_SELECTOR, mConf.SERVER_THREADS);
    master.start();
  }

  private boolean mIsStarted;
  private MasterInfo mMasterInfo;
  private InetSocketAddress mMasterAddress;
  private UIWebServer mWebServer;
  private TNonblockingServerSocket mServerTNonblockingServerSocket;
  private TServer mMasterServiceServer;
  private MasterServiceHandler mMasterServiceHandler;
  private Journal mJournal;
  private EditLogProcessor mEditLogProcessor;
  private int mWebPort;

  private int mSelectorThreads;
  private int mAcceptQueueSizePerThread;
  private int mWorkerThreads;
  private boolean mZookeeperMode = false;

  private LeaderSelectorClient mLeaderSelectorClient = null;

  /** metadata port */
  private final int mPort;

  public TachyonMaster(InetSocketAddress address, int webPort, int selectorThreads,
      int acceptQueueSizePerThreads, int workerThreads) {
    CommonConf.assertValidPort(address);
    CommonConf.assertValidPort(webPort);

    if (CommonConf.get().USE_ZOOKEEPER) {
      mZookeeperMode = true;
    }

    mIsStarted = false;
    mWebPort = webPort;
    mSelectorThreads = selectorThreads;
    mAcceptQueueSizePerThread = acceptQueueSizePerThreads;
    mWorkerThreads = workerThreads;

    try {
      // Extract the port from the generated socket.
      // When running tests, its great to use port '0' so the system will figure out what port to
      // use (any random free port).
      // In a production or any real deployment setup, port '0' should not be used as it will make
      // deployment more complicated.
      mServerTNonblockingServerSocket = new TNonblockingServerSocket(address);
      mPort = NetworkUtils.getPort(mServerTNonblockingServerSocket);

      mMasterAddress = new InetSocketAddress(address.getHostName(), mPort);
      String journalFolder = MasterConf.get().JOURNAL_FOLDER;
      Preconditions.checkState(isFormatted(journalFolder, MasterConf.get().FORMAT_FILE_PREFIX),
          "Tachyon was not formatted!");
      mJournal = new Journal(journalFolder, "image.data", "log.data");
      mMasterInfo = new MasterInfo(mMasterAddress, mJournal);

      if (mZookeeperMode) {
        CommonConf conf = CommonConf.get();
        // InetSocketAddress.toString causes test issues, so build the string by hand
        String name = mMasterAddress.getAddress().getHostName() + ":" + mMasterAddress.getPort();
        mLeaderSelectorClient =
            new LeaderSelectorClient(conf.ZOOKEEPER_ADDRESS, conf.ZOOKEEPER_ELECTION_PATH,
                conf.ZOOKEEPER_LEADER_PATH, name);
        mEditLogProcessor = new EditLogProcessor(mJournal, journalFolder, mMasterInfo);
        Thread logProcessor = new Thread(mEditLogProcessor);
        logProcessor.start();
      }
    } catch (Exception e) {
      LOG.error(e.getMessage(), e);
      throw Throwables.propagate(e);
    }
  }

  /**
   * Get MasterInfo instance for Unit Test
   * 
   * @return MasterInfo of the Master
   */
  MasterInfo getMasterInfo() {
    return mMasterInfo;
  }

  /**
   * Get the port used by unit test only
   */
  int getMetaPort() {
    return mPort;
  }

  private boolean isFormatted(String folder, String path) throws IOException {
    if (!folder.endsWith(Constants.PATH_SEPARATOR)) {
      folder += Constants.PATH_SEPARATOR;
    }
    UnderFileSystem ufs = UnderFileSystem.get(folder);
    String[] files = ufs.list(folder);
    if (files == null) {
      return false;
    }
    for (String file : files) {
      if (file.startsWith(path)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Get wehether the system is the leader under zookeeper mode, for unit test only.
   * 
   * @return true if the system is the leader under zookeeper mode, false otherwise.
   */
  boolean isStarted() {
    return mIsStarted;
  }

  /**
   * Get whether the system is for zookeeper mode, for unit test only.
   * 
   * @return true if the master is under zookeeper mode, false otherwise.
   */
  boolean isZookeeperMode() {
    return mZookeeperMode;
  }

  private void setup() throws IOException, TTransportException {
    if (mZookeeperMode) {
      mEditLogProcessor.stop();
    }
    mMasterInfo.init();

    mWebServer =
        new UIWebServer("Tachyon Master Server", new InetSocketAddress(
            mMasterAddress.getHostName(), mWebPort), mMasterInfo);

    mMasterServiceHandler = new MasterServiceHandler(mMasterInfo);
    MasterService.Processor<MasterServiceHandler> masterServiceProcessor =
        new MasterService.Processor<MasterServiceHandler>(mMasterServiceHandler);

    mMasterServiceServer =
        new TThreadedSelectorServer(new TThreadedSelectorServer.Args(
            mServerTNonblockingServerSocket).processor(masterServiceProcessor)
            .selectorThreads(mSelectorThreads).acceptQueueSizePerThread(mAcceptQueueSizePerThread)
            .workerThreads(mWorkerThreads));

    mIsStarted = true;
  }

  public void start() {
    if (mZookeeperMode) {
      try {
        mLeaderSelectorClient.start();
      } catch (IOException e) {
        LOG.error(e.getMessage(), e);
        throw Throwables.propagate(e);
      }

      Thread currentThread = Thread.currentThread();
      mLeaderSelectorClient.setCurrentMasterThread(currentThread);
      boolean running = false;
      while (true) {
        if (mLeaderSelectorClient.isLeader()) {
          if (!running) {
            running = true;
            try {
              setup();
            } catch (IOException e) {
              LOG.error(e.getMessage(), e);
              throw Throwables.propagate(e);
            } catch (TTransportException e) {
              LOG.error(e.getMessage(), e);
              throw Throwables.propagate(e);
            }
            mWebServer.startWebServer();
            LOG.info("The master (leader) server started @ " + mMasterAddress);
            mMasterServiceServer.serve();
            LOG.info("The master (previous leader) server ended @ " + mMasterAddress);
            mJournal.close();
          }
        } else {
          if (running) {
            mMasterServiceServer.stop();
            running = false;
          }
        }

        CommonUtils.sleepMs(LOG, 100);
      }
    } else {
      try {
        setup();
      } catch (IOException e) {
        LOG.error(e.getMessage(), e);
        throw Throwables.propagate(e);
      } catch (TTransportException e) {
        LOG.error(e.getMessage(), e);
        throw Throwables.propagate(e);
      }

      mWebServer.startWebServer();
      LOG.info("Tachyon Master version " + Version.VERSION + " started @ " + mMasterAddress);
      mMasterServiceServer.serve();
      LOG.info("Tachyon Master version " + Version.VERSION + " ended @ " + mMasterAddress);
    }
  }

  public void stop() throws Exception {
    if (mIsStarted) {
      mWebServer.shutdownWebServer();
      mMasterInfo.stop();
      mMasterServiceServer.stop();
      mServerTNonblockingServerSocket.close();
      mIsStarted = false;
    }
    if (mZookeeperMode) {
      if (mLeaderSelectorClient != null) {
        mLeaderSelectorClient.close();
      }
      if (mEditLogProcessor != null) {
        mEditLogProcessor.stop();
      }
    }
  }
}
