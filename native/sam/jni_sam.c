int debug = 0;
#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include "sam.h"
#include "reciter.h"

JNIEXPORT jbyteArray JNICALL Java_com_ultron_assistant_UltronSamSynth_nativeRender(
    JNIEnv *env, jclass clazz, jstring text, jint speed, jint pitch, jint throat, jint mouth) {

    if (!text) return NULL;

    const char *nativeString = (*env)->GetStringUTFChars(env, text, 0);
    if (!nativeString) return NULL;

    char input[256];
    memset(input, 0, sizeof(input));
    strncpy(input, nativeString, 250);
    (*env)->ReleaseStringUTFChars(env, text, nativeString);

    // Convert to uppercase ASCII and append terminator '['
    int inLen = strlen(input);
    if (inLen == 0) return NULL;

    for (int i = 0; i < inLen; i++) {
        if (input[i] >= 'a' && input[i] <= 'z') {
            input[i] -= 32;
        }
    }
    input[inLen] = '[';
    input[inLen + 1] = '\0';

    if (!TextToPhonemes((unsigned char*)input)) {
        return NULL;
    }

    SetSpeed((unsigned char)speed);
    SetPitch((unsigned char)pitch);
    SetThroat((unsigned char)throat);
    SetMouth((unsigned char)mouth);

    SetInput(input);
    if (!SAMMain()) {
        return NULL;
    }

    char *buf = GetBuffer();
    int len = GetBufferLength() / 50;
    if (!buf || len <= 0) return NULL;

    jbyteArray result = (*env)->NewByteArray(env, len);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, len, (const jbyte*)buf);
    }
    return result;
}
