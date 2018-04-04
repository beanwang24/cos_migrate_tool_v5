package com.qcloud.cos_migrate_tool.task;

import java.io.File;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

import com.qcloud.cos.transfer.TransferManager;
import com.qcloud.cos_migrate_tool.config.CopyFromQiniuConfig;
import com.qcloud.cos_migrate_tool.config.MigrateType;
import com.qcloud.cos_migrate_tool.meta.TaskStatics;
import com.qcloud.cos_migrate_tool.record.MigrateCompetitorRecordElement;
import com.qcloud.cos_migrate_tool.record.RecordDb;
import com.qcloud.cos_migrate_tool.utils.Downloader;
import com.qiniu.util.Auth;

public class MigrateQiniuTask extends Task {
	private Auth auth;
	private String srcKey;
	private long fileSize;
	private String etag;

	public MigrateQiniuTask(CopyFromQiniuConfig config, Auth auth, String srcKey, long fileSize, String etag,
			TransferManager smallFileTransfer, TransferManager bigFileTransfer, RecordDb recordDb,
			Semaphore semaphore) {
	    super(semaphore, config, smallFileTransfer, bigFileTransfer, recordDb);
		this.config = config;
		this.srcKey = srcKey;
		this.fileSize = fileSize;
		this.etag = etag;
		this.auth = auth;
		if (srcKey.startsWith("/")) {
			this.srcKey = srcKey.substring(1);
		}
	}

	private String buildCOSPath() {
        String srcPrefix = ((CopyFromQiniuConfig)config).getSrcPrefix();
        int lastDelimiter = srcPrefix.lastIndexOf("/");
        String keyName = srcKey.substring(lastDelimiter + 1);
        String cosPrefix = config.getCosPath();
        if (cosPrefix.endsWith("/")) {
            return cosPrefix + keyName;
        } else {
            return cosPrefix + "/" + keyName;
        }
	}

	@Override
	public void doTask() {

		String cosPath = buildCOSPath();

		String localPath = config.getTempFolderPath() + ThreadLocalRandom.current().nextLong();

		MigrateCompetitorRecordElement qiniuRecordElement = new MigrateCompetitorRecordElement(
				MigrateType.MIGRATE_FROM_QINIU, config.getBucketName(), cosPath, etag, fileSize);
		if (isExist(qiniuRecordElement)) {
			TaskStatics.instance.addSkipCnt();
			return;
		}

		// generate download url
		String baseUrl = "http://" + ((CopyFromQiniuConfig)config).getSrcEndpoint() + "/" + srcKey;
		String url = auth.privateDownloadUrl(baseUrl, 3600);

		File localFile = new File(localPath);

		// download
		boolean downloadSucc = false;
		try {
			downloadSucc = Downloader.instance.downFile(url, localFile);
		} catch (Exception e) {
			TaskStatics.instance.addFailCnt();
			log.error("download fail url:{} msg:{}", url, e.getMessage());
			localFile.deleteOnExit();
			return;
		}

		if (!downloadSucc) {
			log.error("download fail url:{}", url);
			TaskStatics.instance.addFailCnt();
			return;
		}

		// upload
		if (!localFile.exists()) {
			String errMsg = String.format("[fail] taskInfo: %s. file: %s not exist, srcKey: %s",
					qiniuRecordElement.buildKey(), localPath, srcKey);
			System.err.println(errMsg);
			log.error(errMsg);
			TaskStatics.instance.addFailCnt();
			return;
		}
		
		if (localFile.length() != this.fileSize) {
			log.error("download size[{}] != list size[{}]", localFile.length(), this.fileSize);
			TaskStatics.instance.addFailCnt();
			return;
		}

		try {
			uploadFile(config.getBucketName(), cosPath, localFile, config.getStorageClass(),
					config.isEntireFileMd5Attached());
			saveRecord(qiniuRecordElement);
			TaskStatics.instance.addSuccessCnt();
			String printMsg = String.format("[ok] task_info: %s", qiniuRecordElement.buildKey());
			System.out.println(printMsg);
			log.info(printMsg);
		} catch (Exception e) {
			String printMsg = String.format("[fail] task_info: %s", qiniuRecordElement.buildKey());
			System.err.println(printMsg);
			log.error("[fail] task_info: {}, exception: {}", qiniuRecordElement.buildKey(), e.toString());
			TaskStatics.instance.addFailCnt();
		} finally {
			localFile.delete();
		}
	}
}
