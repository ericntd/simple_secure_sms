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

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.thoughtcrime.securesms.crypto.KeyExchangeMessage;
import org.thoughtcrime.securesms.crypto.KeyUtil;
import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.keys.LocalKeyRecord;
import org.thoughtcrime.securesms.database.keys.RemoteKeyRecord;
import org.thoughtcrime.securesms.database.keys.SessionRecord;
import org.thoughtcrime.securesms.protocol.Message;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.sms.OutgoingKeyExchangeMessage;
import org.thoughtcrime.securesms.util.Conversions;

import textsecure.service.KeyCachingServiceSSS;
import textsecure.sms.MessageSenderSSS;

/**
 * This class processes key exchange interactions.
 *
 * @author Moxie Marlinspike
 */

public class KeyExchangeProcessorSSS {
	// debugging
	private final String TAG = "KeyExchangeProcessorSSS";

  public static final String SECURITY_UPDATE_EVENT = "org.thoughtcrime.securesms.KEY_EXCHANGE_UPDATE";

  private Context context;
  private Recipient recipient;
  private MasterSecret masterSecret;
  private LocalKeyRecord localKeyRecord;
  private RemoteKeyRecord remoteKeyRecord;
  private SessionRecord sessionRecord;

  public KeyExchangeProcessorSSS(Context context, MasterSecret masterSecret, Recipient recipient) {
    this.context         = context;
    this.recipient       = recipient;
    this.masterSecret    = masterSecret;

    this.remoteKeyRecord = new RemoteKeyRecord(context, recipient);
    this.localKeyRecord  = new LocalKeyRecord(context, masterSecret, recipient);
    this.sessionRecord   = new SessionRecord(context, masterSecret, recipient);
  }

  public boolean isTrusted(KeyExchangeMessage message) {
    if (!message.hasIdentityKey()) {
      return false;
    }

    return DatabaseFactory.getIdentityDatabase(context).isValidIdentity(masterSecret, recipient,
                                                                        message.getIdentityKey());
  }

  public boolean hasInitiatedSession() {
    return localKeyRecord.getCurrentKeyPair() != null;
  }

  private boolean needsResponseFromUs() {
    return !hasInitiatedSession() || remoteKeyRecord.getCurrentRemoteKey() != null;
  }

  public boolean isStale(KeyExchangeMessage message) {
    int responseKeyId = Conversions.highBitsToMedium(message.getPublicKey().getId());

    Log.w(TAG, "Key Exchange High ID Bits: "  + responseKeyId);

    return responseKeyId != 0 &&
      (localKeyRecord.getCurrentKeyPair() != null && localKeyRecord.getCurrentKeyPair().getId() != responseKeyId);
  }

  public boolean processKeyExchangeMessage(KeyExchangeMessage message, long threadId) {
    int initiateKeyId = Conversions.lowBitsToMedium(message.getPublicKey().getId());
    message.getPublicKey().setId(initiateKeyId);

    if (needsResponseFromUs()) {
      localKeyRecord                = KeyUtil.initializeRecordFor(recipient, context, masterSecret);
      KeyExchangeMessage ourMessage = new KeyExchangeMessage(context, masterSecret, Math.min(Message.SUPPORTED_VERSION, message.getMaxVersion()), localKeyRecord, initiateKeyId);
      OutgoingKeyExchangeMessage textMessage = new OutgoingKeyExchangeMessage(recipient, ourMessage.serialize());
      Log.w(TAG, "Responding with key exchange message fingerprint: " + ourMessage.getPublicKey().getFingerprint());
      Log.w(TAG, "Which has a local key record fingerprint: " + localKeyRecord.getCurrentKeyPair().getPublicKey().getFingerprint());
      MessageSenderSSS.send(context, masterSecret, textMessage, threadId);
    }

    remoteKeyRecord.setCurrentRemoteKey(message.getPublicKey());
    remoteKeyRecord.setLastRemoteKey(message.getPublicKey());
    remoteKeyRecord.save();

    sessionRecord.setSessionId(localKeyRecord.getCurrentKeyPair().getPublicKey().getFingerprintBytes(),
                               remoteKeyRecord.getCurrentRemoteKey().getFingerprintBytes());
    sessionRecord.setIdentityKey(message.getIdentityKey());
    sessionRecord.setSessionVersion(Math.min(Message.SUPPORTED_VERSION, message.getMaxVersion()));

    Log.w(TAG, "Setting session version: " + Math.min(Message.SUPPORTED_VERSION, message.getMaxVersion()));

    sessionRecord.save();

    if (message.hasIdentityKey()) {
      DatabaseFactory.getIdentityDatabase(context)
                     .saveIdentity(masterSecret, recipient, message.getIdentityKey());
    }

    DecryptingQueueSSS.scheduleRogueMessages(context, masterSecret, recipient);

    Intent intent = new Intent(SECURITY_UPDATE_EVENT);
    intent.putExtra("thread_id", threadId);
    intent.setPackage(context.getPackageName());
    context.sendBroadcast(intent, KeyCachingServiceSSS.KEY_PERMISSION);
    Log.w(TAG, "Sending confirmation of the secure session");

    return true;
  }

}
