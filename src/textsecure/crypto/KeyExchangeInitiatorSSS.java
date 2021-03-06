/**
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package textsecure.crypto;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.util.Log;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.crypto.KeyExchangeMessage;
import org.thoughtcrime.securesms.crypto.KeyUtil;
import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.database.keys.LocalKeyRecord;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.sms.OutgoingKeyExchangeMessage;

import textsecure.sms.MessageSenderSSS;

public class KeyExchangeInitiatorSSS {
	// debugging
	private static final String TAG = "KeyExchangeInitiatorSSS";

  public static void initiate(final Context context, final MasterSecret masterSecret, final Recipient recipient, boolean promptOnExisting) {
    if (promptOnExisting && hasInitiatedSession(context, masterSecret, recipient)) {
      AlertDialog.Builder dialog = new AlertDialog.Builder(context);
      dialog.setTitle(R.string.KeyExchangeInitiator_initiate_despite_existing_request_question);
      dialog.setMessage(R.string.KeyExchangeInitiator_youve_already_sent_a_session_initiation_request_to_this_recipient_are_you_sure);
      dialog.setIcon(android.R.drawable.ic_dialog_alert);
      dialog.setCancelable(true);
      dialog.setPositiveButton(R.string.KeyExchangeInitiator_send, new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int which) {
          initiateKeyExchange(context, masterSecret, recipient);
        }
      });
      dialog.setNegativeButton(android.R.string.cancel, null);
      dialog.show();
    } else {
      initiateKeyExchange(context, masterSecret, recipient);
    }
  }

  private static void initiateKeyExchange(Context context, MasterSecret masterSecret, Recipient recipient) {
    LocalKeyRecord record                  = KeyUtil.initializeRecordFor(recipient, context, masterSecret);
    KeyExchangeMessage message             = new KeyExchangeMessage(context, masterSecret, 1, record, 0);
    OutgoingKeyExchangeMessage textMessage = new OutgoingKeyExchangeMessage(recipient, message.serialize());

    Log.w(TAG, "Sending public key: " + record.getCurrentKeyPair().getPublicKey().getFingerprint());

    MessageSenderSSS.send(context, masterSecret, textMessage, -1);
  }

  private static boolean hasInitiatedSession(Context context, MasterSecret masterSecret, Recipient recipient) {
    return
      LocalKeyRecord.hasRecord(context, recipient) &&
      new LocalKeyRecord(context, masterSecret, recipient).getCurrentKeyPair() != null;
  }
}
