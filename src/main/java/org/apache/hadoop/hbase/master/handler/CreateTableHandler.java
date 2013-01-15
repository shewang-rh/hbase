/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.master.handler;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.NotAllMetaRegionsOnlineException;
import org.apache.hadoop.hbase.Server;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableExistsException;
import org.apache.hadoop.hbase.catalog.CatalogTracker;
import org.apache.hadoop.hbase.catalog.MetaEditor;
import org.apache.hadoop.hbase.catalog.MetaReader;
import org.apache.hadoop.hbase.executor.EventHandler;
import org.apache.hadoop.hbase.master.AssignmentManager;
import org.apache.hadoop.hbase.master.MasterFileSystem;
import org.apache.hadoop.hbase.master.ServerManager;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.util.FSTableDescriptors;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.zookeeper.KeeperException;

/**
 * Handler to create a table.
 */
@InterfaceAudience.Private
public class CreateTableHandler extends EventHandler {
  private static final Log LOG = LogFactory.getLog(CreateTableHandler.class);
  protected MasterFileSystem fileSystemManager;
  protected final HTableDescriptor hTableDescriptor;
  protected Configuration conf;
  protected final AssignmentManager assignmentManager;
  protected final CatalogTracker catalogTracker;
  protected final ServerManager serverManager;
  private final HRegionInfo [] newRegions;

  public CreateTableHandler(Server server, MasterFileSystem fileSystemManager,
    ServerManager serverManager, HTableDescriptor hTableDescriptor,
    Configuration conf, HRegionInfo [] newRegions,
    CatalogTracker catalogTracker, AssignmentManager assignmentManager)
    throws NotAllMetaRegionsOnlineException, TableExistsException,
    IOException {
    super(server, EventType.C_M_CREATE_TABLE);

    this.fileSystemManager = fileSystemManager;
    this.serverManager = serverManager;
    this.hTableDescriptor = hTableDescriptor;
    this.conf = conf;
    this.newRegions = newRegions;
    this.catalogTracker = catalogTracker;
    this.assignmentManager = assignmentManager;

    int timeout = conf.getInt("hbase.client.catalog.timeout", 10000);
    // Need META availability to create a table
    try {
      if(catalogTracker.waitForMeta(timeout) == null) {
        throw new NotAllMetaRegionsOnlineException();
      }
    } catch (InterruptedException e) {
      LOG.warn("Interrupted waiting for meta availability", e);
      throw new IOException(e);
    }

    String tableName = this.hTableDescriptor.getNameAsString();
    if (MetaReader.tableExists(catalogTracker, tableName)) {
      throw new TableExistsException(tableName);
    }

    // If we have multiple client threads trying to create the table at the
    // same time, given the async nature of the operation, the table
    // could be in a state where .META. table hasn't been updated yet in
    // the process() function.
    // Use enabling state to tell if there is already a request for the same
    // table in progress. This will introduce a new zookeeper call. Given
    // createTable isn't a frequent operation, that should be ok.
    try {
      if (!this.assignmentManager.getZKTable().checkAndSetEnablingTable(tableName))
        throw new TableExistsException(tableName);
    } catch (KeeperException e) {
      throw new IOException("Unable to ensure that the table will be" +
        " enabling because of a ZooKeeper issue", e);
    }
  }


  @Override
  public String toString() {
    String name = "UnknownServerName";
    if(server != null && server.getServerName() != null) {
      name = server.getServerName().toString();
    }
    return getClass().getSimpleName() + "-" + name + "-" + getSeqid() + "-" +
      this.hTableDescriptor.getNameAsString();
  }

  @Override
  public void process() {
    String tableName = this.hTableDescriptor.getNameAsString();
    try {
      LOG.info("Attempting to create the table " + tableName);
      handleCreateTable(tableName);
    } catch (IOException e) {
      LOG.error("Error trying to create the table " + tableName, e);
    } catch (KeeperException e) {
      LOG.error("Error trying to create the table " + tableName, e);
    }
  }

  private void handleCreateTable(String tableName) throws IOException,
      KeeperException {
    // TODO: Currently we make the table descriptor and as side-effect the
    // tableDir is created.  Should we change below method to be createTable
    // where we create table in tmp dir with its table descriptor file and then
    // do rename to move it into place?
    FSTableDescriptors.createTableDescriptor(this.hTableDescriptor, this.conf);

    List<HRegionInfo> regionInfos = handleCreateRegions(this.hTableDescriptor.getNameAsString());

    // 4. Trigger immediate assignment of the regions in round-robin fashion
    List<ServerName> servers = serverManager.getOnlineServersList();
    // Remove the deadNotExpired servers from the server list.
    assignmentManager.removeDeadNotExpiredServers(servers);
    try {
      this.assignmentManager.assignUserRegions(regionInfos, servers);
    } catch (InterruptedException ie) {
      LOG.error("Caught " + ie + " during round-robin assignment");
      throw new IOException(ie);
    }

    // 5. Set table enabled flag up in zk.
    try {
      assignmentManager.getZKTable().
        setEnabledTable(this.hTableDescriptor.getNameAsString());
    } catch (KeeperException e) {
      throw new IOException("Unable to ensure that the table will be" +
        " enabled because of a ZooKeeper issue", e);
    }
  }

  protected List<HRegionInfo> handleCreateRegions(String tableName) throws IOException {
    int regionNumber = newRegions.length;
    ThreadPoolExecutor regionOpenAndInitThreadPool = getRegionOpenAndInitThreadPool(
        "RegionOpenAndInitThread-" + tableName, regionNumber);
    CompletionService<HRegion> completionService = new ExecutorCompletionService<HRegion>(
        regionOpenAndInitThreadPool);

    List<HRegionInfo> regionInfos = new ArrayList<HRegionInfo>();
    for (final HRegionInfo newRegion : newRegions) {
      completionService.submit(new Callable<HRegion>() {
        public HRegion call() throws IOException {

          // 1. Create HRegion
          HRegion region = HRegion.createHRegion(newRegion,
              fileSystemManager.getRootDir(), conf, hTableDescriptor, null,
              false, true);

          // 2. Close the new region to flush to disk. Close log file too.
          region.close();
          return region;
        }
      });
    }
    try {
      // 3. wait for all regions to finish creation
      for (int i = 0; i < regionNumber; i++) {
        Future<HRegion> future = completionService.take();
        HRegion region = future.get();
        regionInfos.add(region.getRegionInfo());
      }
    } catch (InterruptedException e) {
      throw new InterruptedIOException(e.getMessage());
    } catch (ExecutionException e) {
      throw new IOException(e.getCause());
    } finally {
      regionOpenAndInitThreadPool.shutdownNow();
    }
    if (regionInfos.size() > 0) {
      MetaEditor.addRegionsToMeta(this.catalogTracker, regionInfos);
    }
    return regionInfos;
  }

  protected ThreadPoolExecutor getRegionOpenAndInitThreadPool(
      final String threadNamePrefix, int regionNumber) {
    int maxThreads = Math.min(regionNumber, conf.getInt(
        "hbase.hregion.open.and.init.threads.max", 10));
    ThreadPoolExecutor openAndInitializeThreadPool = Threads
    .getBoundedCachedThreadPool(maxThreads, 30L, TimeUnit.SECONDS,
        new ThreadFactory() {
          private int count = 1;

          public Thread newThread(Runnable r) {
            Thread t = new Thread(r, threadNamePrefix + "-" + count++);
            return t;
          }
        });
    return openAndInitializeThreadPool;
  }
}
