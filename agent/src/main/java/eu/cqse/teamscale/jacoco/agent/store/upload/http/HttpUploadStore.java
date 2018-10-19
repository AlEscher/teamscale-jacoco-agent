package eu.cqse.teamscale.jacoco.agent.store.upload.http;

import eu.cqse.teamscale.client.EReportFormat;
import eu.cqse.teamscale.jacoco.agent.store.file.TimestampedFileStore;
import eu.cqse.teamscale.jacoco.agent.store.upload.HttpUploadStoreBase;
import okhttp3.HttpUrl;
import okhttp3.ResponseBody;
import org.conqat.lib.commons.assertion.CCSMAssert;
import retrofit2.Response;
import retrofit2.Retrofit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Uploads XMLs and metadata via HTTP multi-part form data requests.
 */
public class HttpUploadStore extends HttpUploadStoreBase<IHttpUploadApi> {
	/** Constructor. */
	public HttpUploadStore(TimestampedFileStore failureStore, HttpUrl uploadUrl, List<Path> additionalMetaDataFiles) {
		super(failureStore, uploadUrl, additionalMetaDataFiles);
	}

	@Override
	protected IHttpUploadApi getApi(Retrofit retrofit) {
		return retrofit.create(IHttpUploadApi.class);
	}

	@Override
	protected void checkReportFormat(EReportFormat format) {
		CCSMAssert.isTrue(format == EReportFormat.JACOCO, "HTTP upload does only support JaCoCo " +
				"coverage and cannot be used with Test Impact mode.");
	}

	@Override
	protected Response<ResponseBody> uploadCoverageZip(byte[] zipFileBytes) throws IOException {
		return api.uploadCoverageZip(zipFileBytes);
	}

	/** {@inheritDoc} */
	@Override
	public String describe() {
		return "Uploading to " + uploadUrl + " (fallback in case of network errors to: " + failureStore.describe()
				+ ")";
	}

}
