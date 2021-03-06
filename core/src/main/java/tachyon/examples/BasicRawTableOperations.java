package tachyon.examples;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.apache.log4j.Logger;

import tachyon.Constants;
import tachyon.Version;
import tachyon.client.OutStream;
import tachyon.client.TachyonByteBuffer;
import tachyon.client.TachyonFS;
import tachyon.client.TachyonFile;
import tachyon.client.WriteType;
import tachyon.client.table.RawColumn;
import tachyon.client.table.RawTable;

public class BasicRawTableOperations {
  private static Logger LOG = Logger.getLogger(Constants.LOGGER_TYPE);

  private static final int COLS = 3;
  private static TachyonFS sTachyonClient;
  private static String sTablePath = null;
  private static int mId;
  private static WriteType sWriteType = null;
  private static int sDataLength = 20;
  private static int sMetadataLength = 5;
  private static boolean sPass = true;

  public static void createRawTable() throws IOException {
    ByteBuffer data = ByteBuffer.allocate(sMetadataLength * 4);
    data.order(ByteOrder.nativeOrder());
    for (int k = -sMetadataLength; k < 0; k ++) {
      data.putInt(k);
    }
    data.flip();
    mId = sTachyonClient.createRawTable(sTablePath, 3, data);
  }

  public static void main(String[] args) throws IOException {
    if (args.length != 3) {
      System.out.println("java -cp target/tachyon-" + Version.VERSION
          + "-jar-with-dependencies.jar "
          + "tachyon.examples.BasicRawTableOperations <TachyonMasterAddress> <FilePath>");
      System.exit(-1);
    }
    sTachyonClient = TachyonFS.get(args[0]);
    sTablePath = args[1];
    sWriteType = WriteType.getOpType(args[2]);
    createRawTable();
    write();
    read();
    Utils.printPassInfo(sPass);
    System.exit(0);
  }

  public static void read() throws IOException {
    LOG.debug("Reading data...");
    RawTable rawTable = sTachyonClient.getRawTable(mId);
    ByteBuffer metadata = rawTable.getMetadata();
    LOG.debug("Metadata: ");
    metadata.order(ByteOrder.nativeOrder());
    for (int k = -sMetadataLength; k < 0; k ++) {
      sPass = sPass && (metadata.getInt() == k);
    }

    for (int column = 0; column < COLS; column ++) {
      RawColumn rawColumn = rawTable.getRawColumn(column);
      TachyonFile tFile = rawColumn.getPartition(0);

      TachyonByteBuffer buf = tFile.readByteBuffer(0);
      if (buf == null) {
        tFile.recache();
        buf = tFile.readByteBuffer(0);
      }
      buf.DATA.order(ByteOrder.nativeOrder());
      for (int k = 0; k < sDataLength; k ++) {
        sPass = sPass && (buf.DATA.getInt() == k);
      }
      buf.close();
    }
  }

  public static void write() throws IOException {
    RawTable rawTable = sTachyonClient.getRawTable(sTablePath);

    LOG.debug("Writing data...");
    for (int column = 0; column < COLS; column ++) {
      RawColumn rawColumn = rawTable.getRawColumn(column);
      if (!rawColumn.createPartition(0)) {
        throw new IOException("Failed to create partition in table " + sTablePath
            + " under column " + column);
      }

      ByteBuffer buf = ByteBuffer.allocate(80);
      buf.order(ByteOrder.nativeOrder());
      for (int k = 0; k < sDataLength; k ++) {
        buf.putInt(k);
      }
      buf.flip();

      TachyonFile tFile = rawColumn.getPartition(0);
      OutStream os = tFile.getOutStream(sWriteType);
      os.write(buf.array());
      os.close();
    }
  }
}