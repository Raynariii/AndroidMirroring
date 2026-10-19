// JNI bridge to BoringSSL's SPAKE2, which Android's Wireless debugging pairing uses.
// Mirrors adb's pairing_auth: the client is "alice", names include the trailing NUL.

#include <jni.h>
#include <stdint.h>
#include <openssl/curve25519.h>

static const uint8_t kClientName[] = "adb pair client";
static const uint8_t kServerName[] = "adb pair server";

JNIEXPORT jlong JNICALL
Java_com_androidmirroring_activation_Spake2_nativeNew(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (jlong) (intptr_t) SPAKE2_CTX_new(spake2_role_alice, kClientName, sizeof(kClientName),
                                             kServerName, sizeof(kServerName));
}

JNIEXPORT void JNICALL
Java_com_androidmirroring_activation_Spake2_nativeFree(JNIEnv *env, jclass clazz, jlong ctx) {
    (void) env;
    (void) clazz;
    SPAKE2_CTX_free((SPAKE2_CTX *) (intptr_t) ctx);
}

JNIEXPORT jbyteArray JNICALL
Java_com_androidmirroring_activation_Spake2_nativeGenerateMsg(JNIEnv *env, jclass clazz, jlong ctx,
                                                              jbyteArray password) {
    (void) clazz;
    uint8_t msg[SPAKE2_MAX_MSG_SIZE];
    size_t msg_len = 0;
    jsize password_len = (*env)->GetArrayLength(env, password);
    jbyte *password_bytes = (*env)->GetByteArrayElements(env, password, NULL);
    int ok = SPAKE2_generate_msg((SPAKE2_CTX *) (intptr_t) ctx, msg, &msg_len, sizeof(msg),
                                 (const uint8_t *) password_bytes, (size_t) password_len);
    (*env)->ReleaseByteArrayElements(env, password, password_bytes, JNI_ABORT);
    if (!ok) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, (jsize) msg_len);
    (*env)->SetByteArrayRegion(env, out, 0, (jsize) msg_len, (const jbyte *) msg);
    return out;
}

JNIEXPORT jbyteArray JNICALL
Java_com_androidmirroring_activation_Spake2_nativeProcessMsg(JNIEnv *env, jclass clazz, jlong ctx,
                                                             jbyteArray their_msg) {
    (void) clazz;
    uint8_t key[SPAKE2_MAX_KEY_SIZE];
    size_t key_len = 0;
    jsize msg_len = (*env)->GetArrayLength(env, their_msg);
    jbyte *msg = (*env)->GetByteArrayElements(env, their_msg, NULL);
    int ok = SPAKE2_process_msg((SPAKE2_CTX *) (intptr_t) ctx, key, &key_len, sizeof(key),
                                (const uint8_t *) msg, (size_t) msg_len);
    (*env)->ReleaseByteArrayElements(env, their_msg, msg, JNI_ABORT);
    if (!ok) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, (jsize) key_len);
    (*env)->SetByteArrayRegion(env, out, 0, (jsize) key_len, (const jbyte *) key);
    return out;
}
