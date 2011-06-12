/**
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

package org.apache.hadoop.mapreduce.v2;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.security.PrivilegedExceptionAction;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import junit.framework.Assert;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.FailingMapper;
import org.apache.hadoop.RandomTextWriterJob;
import org.apache.hadoop.RandomTextWriterJob.RandomInputFormat;
import org.apache.hadoop.SleepJob;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobStatus;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.TaskCompletionEvent;
import org.apache.hadoop.mapreduce.TaskID;
import org.apache.hadoop.mapreduce.TaskType;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputCommitter;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.server.YarnServerConfig;
import org.apache.hadoop.yarn.server.nodemanager.NMConfig;
import org.apache.hadoop.yarn.server.resourcemanager.RMConfig;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestMRJobs {

  private static final Log LOG = LogFactory.getLog(TestMRJobs.class);

  protected static MiniMRYarnCluster mrCluster;

  private static Configuration conf = new Configuration();
  private static FileSystem localFs;
  static {
    try {
      localFs = FileSystem.getLocal(conf);
    } catch (IOException io) {
      throw new RuntimeException("problem getting local fs", io);
    }
  }

  private static Path TEST_ROOT_DIR = new Path("target",
      TestMRJobs.class.getName() + "-tmpDir").makeQualified(localFs);
  static Path APP_JAR = new Path(TEST_ROOT_DIR, "MRAppJar.jar");

  @BeforeClass
  public static void setup() throws IOException {

    if (!(new File(MiniMRYarnCluster.APPJAR)).exists()) {
      LOG.info("MRAppJar " + MiniMRYarnCluster.APPJAR
               + " not found. Not running test.");
      return;
    }

    if (mrCluster == null) {
      mrCluster = new MiniMRYarnCluster(TestMRJobs.class.getName());
      mrCluster.init(new Configuration());
      mrCluster.start();
    }

    // Copy MRAppJar and make it private. TODO: FIXME. This is a hack to
    // workaround the absent public discache.
    localFs.copyFromLocalFile(new Path(MiniMRYarnCluster.APPJAR), APP_JAR);
    localFs.setPermission(APP_JAR, new FsPermission("700"));
  }

  @AfterClass
  public static void tearDown() {
    if (mrCluster != null) {
      mrCluster.stop();
      mrCluster = null;
    }
  }

  @Test
  public void testSleepJob() throws IOException, InterruptedException,
      ClassNotFoundException { 

    LOG.info("\n\n\nStarting testSleepJob().");

    if (!(new File(MiniMRYarnCluster.APPJAR)).exists()) {
      LOG.info("MRAppJar " + MiniMRYarnCluster.APPJAR
               + " not found. Not running test.");
      return;
    }

    SleepJob sleepJob = new SleepJob();
    sleepJob.setConf(mrCluster.getConfig());

    int numReduces = mrCluster.getConfig().getInt("TestMRJobs.testSleepJob.reduces", 2); // or mrCluster.getConfig().getInt(MRJobConfig.NUM_REDUCES, 2);

    // job with 3 maps (10s) and numReduces reduces (5s), 1 "record" each:
    Job job = sleepJob.createJob(3, numReduces, 10000, 1, 5000, 1);

    job.addFileToClassPath(APP_JAR); // The AppMaster jar itself.
    job.setJarByClass(SleepJob.class);
    job.setMaxMapAttempts(1); // speed up failures
    job.waitForCompletion(true);
    boolean succeeded = job.waitForCompletion(true);
    Assert.assertTrue(succeeded);
    Assert.assertEquals(JobStatus.State.SUCCEEDED, job.getJobState());

    // TODO later:  add explicit "isUber()" checks of some sort (extend
    // JobStatus?)--compare against MRJobConfig.JOB_UBERTASK_ENABLE value
  }

  @Test
  public void testRandomWriter() throws IOException, InterruptedException,
      ClassNotFoundException {

    LOG.info("\n\n\nStarting testRandomWriter().");

    if (!(new File(MiniMRYarnCluster.APPJAR)).exists()) {
      LOG.info("MRAppJar " + MiniMRYarnCluster.APPJAR
               + " not found. Not running test.");
      return;
    }

    RandomTextWriterJob randomWriterJob = new RandomTextWriterJob();
    mrCluster.getConfig().set(RandomTextWriterJob.TOTAL_BYTES, "3072");
    mrCluster.getConfig().set(RandomTextWriterJob.BYTES_PER_MAP, "1024");
    Job job = randomWriterJob.createJob(mrCluster.getConfig());
    Path outputDir = new Path(mrCluster.getTestWorkDir().getAbsolutePath(),
        "random-output");
    FileOutputFormat.setOutputPath(job, outputDir);
    job.addFileToClassPath(APP_JAR); // The AppMaster jar itself.
    job.setJarByClass(RandomTextWriterJob.class);
    job.setMaxMapAttempts(1); // speed up failures
    boolean succeeded = job.waitForCompletion(true);
    Assert.assertTrue(succeeded);
    Assert.assertEquals(JobStatus.State.SUCCEEDED, job.getJobState());
    // Make sure there are three files in the output-dir
    RemoteIterator<FileStatus> iterator =
        FileContext.getFileContext(mrCluster.getConfig()).listStatus(
            outputDir);
    int count = 0;
    while (iterator.hasNext()) {
      FileStatus file = iterator.next();
      if (!file.getPath().getName()
          .equals(FileOutputCommitter.SUCCEEDED_FILE_NAME)) {
        count++;
      }
    }
    Assert.assertEquals("Number of part files is wrong!", 3, count);

    // TODO later:  add explicit "isUber()" checks of some sort
  }

  @Test
  public void testFailingMapper() throws IOException, InterruptedException,
      ClassNotFoundException {

    LOG.info("\n\n\nStarting testFailingMapper().");

    if (!(new File(MiniMRYarnCluster.APPJAR)).exists()) {
      LOG.info("MRAppJar " + MiniMRYarnCluster.APPJAR
               + " not found. Not running test.");
      return;
    }

    Job job = runFailingMapperJob();

    TaskID taskID = new TaskID(job.getJobID(), TaskType.MAP, 0);
    TaskAttemptID aId = new TaskAttemptID(taskID, 0);
    System.out.println("Diagnostics for " + aId + " :");
    for (String diag : job.getTaskDiagnostics(aId)) {
      System.out.println(diag);
    }
    aId = new TaskAttemptID(taskID, 1);
    System.out.println("Diagnostics for " + aId + " :");
    for (String diag : job.getTaskDiagnostics(aId)) {
      System.out.println(diag);
    }
    
    TaskCompletionEvent[] events = job.getTaskCompletionEvents(0, 2);
    Assert.assertEquals(TaskCompletionEvent.Status.FAILED, 
        events[0].getStatus().FAILED);
    Assert.assertEquals(TaskCompletionEvent.Status.FAILED, 
        events[1].getStatus().FAILED);
    Assert.assertEquals(JobStatus.State.FAILED, job.getJobState());

    // TODO later:  add explicit "isUber()" checks of some sort
  }

  protected Job runFailingMapperJob()
  throws IOException, InterruptedException, ClassNotFoundException {
    mrCluster.getConfig().setInt(MRJobConfig.NUM_MAPS, 1);
    mrCluster.getConfig().setInt("mapreduce.task.timeout", 10*1000);//reduce the timeout
    mrCluster.getConfig().setInt(MRJobConfig.MAP_MAX_ATTEMPTS, 2); //reduce the number of attempts

    Job job = new Job(mrCluster.getConfig());

    job.setJarByClass(FailingMapper.class);
    job.setJobName("failmapper");
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(Text.class);
    job.setInputFormatClass(RandomInputFormat.class);
    job.setOutputFormatClass(TextOutputFormat.class);
    job.setMapperClass(FailingMapper.class);
    job.setNumReduceTasks(0);
    
    FileOutputFormat.setOutputPath(job,
        new Path(mrCluster.getTestWorkDir().getAbsolutePath(),
        "failmapper-output"));
    job.addFileToClassPath(APP_JAR); // The AppMaster jar itself.
    boolean succeeded = job.waitForCompletion(true);
    Assert.assertFalse(succeeded);

    return job;
  }

//@Test
  public void testSleepJobWithSecurityOn() throws IOException,
      InterruptedException, ClassNotFoundException {

    LOG.info("\n\n\nStarting testSleepJobWithSecurityOn().");

    if (!(new File(MiniMRYarnCluster.APPJAR)).exists()) {
      return;
    }

    mrCluster.getConfig().set(
        CommonConfigurationKeysPublic.HADOOP_SECURITY_AUTHENTICATION,
        "kerberos");
    mrCluster.getConfig().set(RMConfig.RM_KEYTAB, "/etc/krb5.keytab");
    mrCluster.getConfig().set(NMConfig.NM_KEYTAB, "/etc/krb5.keytab");
    mrCluster.getConfig().set(YarnConfiguration.RM_SERVER_PRINCIPAL_KEY,
        "rm/sightbusy-lx@LOCALHOST");
    mrCluster.getConfig().set(YarnServerConfig.NM_SERVER_PRINCIPAL_KEY,
        "nm/sightbusy-lx@LOCALHOST");
    UserGroupInformation.setConfiguration(mrCluster.getConfig());

    // Keep it in here instead of after RM/NM as multiple user logins happen in
    // the same JVM.
    UserGroupInformation user = UserGroupInformation.getCurrentUser();

    LOG.info("User name is " + user.getUserName());
    for (Token<? extends TokenIdentifier> str : user.getTokens()) {
      LOG.info("Token is " + str.encodeToUrlString());
    }
    user.doAs(new PrivilegedExceptionAction<Void>() {
      @Override
      public Void run() throws Exception {  
        SleepJob sleepJob = new SleepJob();
        sleepJob.setConf(mrCluster.getConfig());
        Job job = sleepJob.createJob(3, 0, 10000, 1, 0, 0);
        // //Job with reduces
        // Job job = sleepJob.createJob(3, 2, 10000, 1, 10000, 1);
        job.addFileToClassPath(APP_JAR); // The AppMaster jar itself.
        job.waitForCompletion(true);
        Assert.assertEquals(JobStatus.State.SUCCEEDED, job.getJobState());
        return null;
      }
    });

    // TODO later:  add explicit "isUber()" checks of some sort
  }

  public static class DistributedCacheChecker extends
      Mapper<LongWritable, Text, NullWritable, NullWritable> {

    @Override
    public void setup(Context context) throws IOException {
      Configuration conf = context.getConfiguration();
      Path[] files = context.getLocalCacheFiles();
      Path[] archives = context.getLocalCacheArchives();
      FileSystem fs = LocalFileSystem.get(conf);

      // Check that 3(2+ appjar) files and 2 archives are present
      Assert.assertEquals(3, files.length);
      Assert.assertEquals(2, archives.length);

      // Check lengths of the files
      Assert.assertEquals(1, fs.getFileStatus(files[0]).getLen());
      Assert.assertTrue(fs.getFileStatus(files[1]).getLen() > 1);

      // Check extraction of the archive
      Assert.assertTrue(fs.exists(new Path(archives[0],
          "distributed.jar.inside3")));
      Assert.assertTrue(fs.exists(new Path(archives[1],
          "distributed.jar.inside4")));

      // Check the class loaders
      LOG.info("Java Classpath: " + System.getProperty("java.class.path"));
      ClassLoader cl = Thread.currentThread().getContextClassLoader();
      // Both the file and the archive should have been added to classpath, so
      // both should be reachable via the class loader.
      Assert.assertNotNull(cl.getResource("distributed.jar.inside2"));
      Assert.assertNotNull(cl.getResource("distributed.jar.inside3"));
      Assert.assertNull(cl.getResource("distributed.jar.inside4"));

      // Check that the symlink for the renaming was created in the cwd;
      File symlinkFile = new File("distributed.first.symlink");
      Assert.assertTrue(symlinkFile.exists());
      Assert.assertEquals(1, symlinkFile.length());
    }
  }

  @Test
  public void testDistributedCache() throws Exception {
    if (!(new File(MiniMRYarnCluster.APPJAR)).exists()) {
      LOG.info("MRAppJar " + MiniMRYarnCluster.APPJAR
           + " not found. Not running test.");
      return;
    }

    // Create a temporary file of length 1.
    Path first = createTempFile("distributed.first", "x");
    // Create two jars with a single file inside them.
    Path second =
        makeJar(new Path(TEST_ROOT_DIR, "distributed.second.jar"), 2);
    Path third =
        makeJar(new Path(TEST_ROOT_DIR, "distributed.third.jar"), 3);
    Path fourth =
        makeJar(new Path(TEST_ROOT_DIR, "distributed.fourth.jar"), 4);

    Job job = Job.getInstance(mrCluster.getConfig());
    job.setJarByClass(DistributedCacheChecker.class);
    job.setMapperClass(DistributedCacheChecker.class);
    job.setOutputFormatClass(NullOutputFormat.class);

    FileInputFormat.setInputPaths(job, first);
    // Creates the Job Configuration
    job.addCacheFile(
        new URI(first.toUri().toString() + "#distributed.first.symlink"));
    job.addFileToClassPath(second);
    job.addFileToClassPath(APP_JAR); // The AppMaster jar itself.
    job.addArchiveToClassPath(third);
    job.addCacheArchive(fourth.toUri());
    job.createSymlink();
    job.setMaxMapAttempts(1); // speed up failures

    job.submit();
    Assert.assertTrue(job.waitForCompletion(false));
  }

  private Path createTempFile(String filename, String contents)
      throws IOException {
    Path path = new Path(TEST_ROOT_DIR, filename);
    FSDataOutputStream os = localFs.create(path);
    os.writeBytes(contents);
    os.close();
    localFs.setPermission(path, new FsPermission("700"));
    return path;
  }

  private Path makeJar(Path p, int index) throws FileNotFoundException,
      IOException {
    FileOutputStream fos =
        new FileOutputStream(new File(p.toUri().getPath()));
    JarOutputStream jos = new JarOutputStream(fos);
    ZipEntry ze = new ZipEntry("distributed.jar.inside" + index);
    jos.putNextEntry(ze);
    jos.write(("inside the jar!" + index).getBytes());
    jos.closeEntry();
    jos.close();
    localFs.setPermission(p, new FsPermission("700"));
    return p;
  }
}
