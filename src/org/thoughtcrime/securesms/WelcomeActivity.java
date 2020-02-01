package org.thoughtcrime.securesms;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import android.view.View;
import android.widget.Button;

import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEventCenter;
import com.b44t.messenger.DcLot;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.thoughtcrime.securesms.connect.ApplicationDcContext;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.qr.RegistrationQrActivity;
import org.thoughtcrime.securesms.util.views.ProgressDialog;

import java.io.File;

public class WelcomeActivity extends BaseActionBarActivity implements DcEventCenter.DcEventDelegate {

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.welcome_activity);

        Button loginButton = findViewById(R.id.login_button);
        View scanQrButton = findViewById(R.id.scan_qr_button);
        View backupButton = findViewById(R.id.backup_button);

        loginButton.setOnClickListener((view) -> startRegistrationActivity());
        scanQrButton.setOnClickListener((view) -> startRegistrationQrActivity());
        backupButton.setOnClickListener((view) -> startImportBackup());

        ApplicationDcContext dcContext = DcHelper.getContext(this);
        dcContext.eventCenter.addObserver(DcContext.DC_EVENT_CONFIGURE_PROGRESS, this);
        dcContext.eventCenter.addObserver(DcContext.DC_EVENT_IMEX_PROGRESS, this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        DcHelper.getContext(this).eventCenter.removeObservers(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
    }

    private void startRegistrationActivity() {
        Intent intent = new Intent(this, RegistrationActivity.class);
        startActivity(intent);
        // no finish() here, the back key should take the user back from RegistrationActivity to WelcomeActivity
    }

    private void startRegistrationQrActivity() {
        new IntentIntegrator(this).setCaptureActivity(RegistrationQrActivity.class).initiateScan();
        // no finish() here, the back key should take the user back from RegistrationActivity to WelcomeActivity
    }

    @SuppressLint("InlinedApi")
    private void startImportBackup() {
        Permissions.with(this)
                .request(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
                .ifNecessary()
                .withPermanentDenialDialog(getString(R.string.perm_explain_access_to_storage_denied))
                .onAllGranted(() -> {
                    ApplicationDcContext dcContext = DcHelper.getContext(this);
                    File imexDir = dcContext.getImexDir();
                    final String backupFile = dcContext.imexHasBackup(imexDir.getAbsolutePath());
                    if (backupFile != null) {
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.import_backup_title)
                                .setMessage(String.format(getResources().getString(R.string.import_backup_ask), backupFile))
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                        startImport(backupFile);
                                })
                                .show();
                    }
                    else {
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.import_backup_title)
                                .setMessage(String.format(getResources().getString(R.string.import_backup_no_backup_found), imexDir.getAbsolutePath()))
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    }
                })
                .execute();
    }

    private ProgressDialog progressDialog = null;
    private void startImport(final String backupFile)
    {
        ApplicationDcContext dcContext = DcHelper.getContext(this);

        if( progressDialog!=null ) {
            progressDialog.dismiss();
            progressDialog = null;
        }

        progressDialog = new ProgressDialog(this);
        progressDialog.setMessage(getResources().getString(R.string.one_moment));
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.setCancelable(false);
        progressDialog.setButton(DialogInterface.BUTTON_NEGATIVE, getResources().getString(android.R.string.cancel), (dialog, which) -> {
            dcContext.stopOngoingProcess();
        });
        progressDialog.show();

        dcContext.captureNextError();
        dcContext.imex(DcContext.DC_IMEX_IMPORT_BACKUP, backupFile);
    }

    @Override
    public void handleEvent(int eventId, Object data1, Object data2) {
        if (eventId== DcContext.DC_EVENT_IMEX_PROGRESS) {
            ApplicationDcContext dcContext = DcHelper.getContext(this);
            long progress = (Long)data1;
            if (progress==0/*error/aborted*/) {
                dcContext.endCaptureNextError();
                progressDialog.dismiss();
                if (dcContext.hasCapturedError()) {
                    new AlertDialog.Builder(this)
                            .setMessage(dcContext.getCapturedError())
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                }
            }
            else if (progress<1000/*progress in permille*/) {
                int percent = (int)progress / 10;
                progressDialog.setMessage(getResources().getString(R.string.one_moment)+String.format(" %d%%", percent));
            }
            else if (progress==1000/*done*/) {
                dcContext.endCaptureNextError();
                progressDialog.dismiss();
                Intent conversationList = new Intent(getApplicationContext(), ConversationListActivity.class);
                startActivity(conversationList);
                finish();
            }
        }
        else if (eventId== DcContext.DC_EVENT_CONFIGURE_PROGRESS) {
            long progress = (Long)data1;
            if (progress==1000/*done*/) {
                finish(); // remove ourself from the activity stack (finishAffinity is available in API 16, we're targeting API 14)
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode==IntentIntegrator.REQUEST_CODE) {
            IntentResult scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
            if (scanResult == null || scanResult.getFormatName() == null) {
                return; // aborted
            }
            ApplicationDcContext dcContext = DcHelper.getContext(this);
            DcLot qrParsed = dcContext.checkQr(scanResult.getContents());
            switch (qrParsed.getState()) {
                case DcContext.DC_QR_ACCOUNT:
                    String domain = qrParsed.getText1();
                    new AlertDialog.Builder(this)
                            .setMessage(String.format("Create new e-mail address on \"%s\" and log in there?", domain))
                            .setPositiveButton(R.string.ok, null)
                            .setNegativeButton(R.string.cancel, null)
                            .setCancelable(false)
                            .show();
                    break;

                default:
                    new AlertDialog.Builder(this)
                            .setMessage("The scanned QR code cannot be used to set up a new account.")
                            .setPositiveButton(R.string.ok, null)
                            .show();
                    break;
            }
        }
    }
}
