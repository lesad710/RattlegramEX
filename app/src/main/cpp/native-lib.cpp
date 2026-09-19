/*
Java native interface to C++ encoder and decoder

Copyright 2022 Ahmet Inan <inan@aicodix.de>
*/

#include <jni.h>
#define assert(expr) do {} while (0)
#include "encoder.hh"
#include "decoder.hh"

static EncoderInterface *encoder;
// Every service instance owns its decoder. An old worker may still be
// finishing shutdown while a new service starts.
static jfieldID decoderField(JNIEnv *env, jobject instance) {
	jclass type = env->GetObjectClass(instance);
	jfieldID field = env->GetFieldID(type, "nativeDecoder", "J");
	env->DeleteLocalRef(type);
	return field;
}

static DecoderInterface *getDecoder(JNIEnv *env, jobject instance) {
	return reinterpret_cast<DecoderInterface *>(env->GetLongField(instance, decoderField(env, instance)));
}

static void setDecoder(JNIEnv *env, jobject instance, DecoderInterface *decoder) {
	env->SetLongField(instance, decoderField(env, instance), reinterpret_cast<jlong>(decoder));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aicodix_rattlegram_MainActivity_createEncoder(
	JNIEnv *,
	jobject,
	jint sampleRate) {
	if (encoder && encoder->rate() == sampleRate)
		return true;
	delete encoder;
	switch (sampleRate) {
		case 8000:
			encoder = new(std::nothrow) Encoder<8000>();
			break;
		case 16000:
			encoder = new(std::nothrow) Encoder<16000>();
			break;
		case 32000:
			encoder = new(std::nothrow) Encoder<32000>();
			break;
		case 44100:
			encoder = new(std::nothrow) Encoder<44100>();
			break;
		case 48000:
			encoder = new(std::nothrow) Encoder<48000>();
			break;
		default:
			encoder = nullptr;
	}
	return encoder != nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aicodix_rattlegram_MainActivity_destroyEncoder(
	JNIEnv *,
	jobject) {
	delete encoder;
	encoder = nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aicodix_rattlegram_MainActivity_produceEncoder(
	JNIEnv *env,
	jobject,
	jshortArray JNI_audioBuffer,
	jint channelSelect) {

	if (!encoder)
		return false;

	jshort *audioBuffer = env->GetShortArrayElements(JNI_audioBuffer, nullptr);
	jboolean okay = false;
	if (audioBuffer) {
		okay = encoder->produce(audioBuffer, channelSelect);
		env->ReleaseShortArrayElements(JNI_audioBuffer, audioBuffer, 0);
	}
	return okay;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aicodix_rattlegram_MainActivity_configureEncoder(
	JNIEnv *env,
	jobject,
	jbyteArray JNI_payload,
	jbyteArray JNI_callSign,
	jint carrierFrequency,
	jint noiseSymbols,
	jboolean fancyHeader) {

	if (!encoder)
		return;

	jbyte *payload, *callSign;
	payload = env->GetByteArrayElements(JNI_payload, nullptr);
	if (!payload)
		goto payloadFail;
	callSign = env->GetByteArrayElements(JNI_callSign, nullptr);
	if (!callSign)
		goto callSignFail;

	encoder->configure(
		reinterpret_cast<uint8_t *>(payload),
		reinterpret_cast<int8_t *>(callSign),
		carrierFrequency,
		noiseSymbols,
		fancyHeader);

	env->ReleaseByteArrayElements(JNI_callSign, callSign, JNI_ABORT);
	callSignFail:
	env->ReleaseByteArrayElements(JNI_payload, payload, JNI_ABORT);
	payloadFail:;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aicodix_rattlegram_SignalMonitor_destroyDecoder(
	JNIEnv *env,
	jobject instance) {
	DecoderInterface *decoder = getDecoder(env, instance);
	delete decoder;
	setDecoder(env, instance, nullptr);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aicodix_rattlegram_SignalMonitor_createDecoder(
	JNIEnv *env,
	jobject instance,
	jint sampleRate) {
	DecoderInterface *decoder = getDecoder(env, instance);
	if (decoder && decoder->rate() == sampleRate)
		return true;
	delete decoder;
	switch (sampleRate) {
		case 8000:
			decoder = new(std::nothrow) Decoder<8000>();
			break;
		case 16000:
			decoder = new(std::nothrow) Decoder<16000>();
			break;
		case 32000:
			decoder = new(std::nothrow) Decoder<32000>();
			break;
		case 44100:
			decoder = new(std::nothrow) Decoder<44100>();
			break;
		case 48000:
			decoder = new(std::nothrow) Decoder<48000>();
			break;
		default:
			decoder = nullptr;
	}
	setDecoder(env, instance, decoder);
	return decoder != nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aicodix_rattlegram_SignalMonitor_fetchDecoder(
	JNIEnv *env,
	jobject instance,
	jbyteArray JNI_payload) {
	DecoderInterface *decoder = getDecoder(env, instance);
	jint status = -1;
	if (decoder) {
		jbyte *payload = env->GetByteArrayElements(JNI_payload, nullptr);
		if (payload) {
			status = decoder->fetch(reinterpret_cast<uint8_t *>(payload));
			env->ReleaseByteArrayElements(JNI_payload, payload, 0);
		}
	}
	return status;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aicodix_rattlegram_SignalMonitor_stagedDecoder(
	JNIEnv *env,
	jobject instance,
	jfloatArray JNI_carrierFrequencyOffset,
	jintArray JNI_operationMode,
	jbyteArray JNI_callSign) {
	DecoderInterface *decoder = getDecoder(env, instance);

	if (!decoder)
		return;

	jint *operationMode;
	jfloat *carrierFrequencyOffset;
	jbyte *callSign;
	carrierFrequencyOffset = env->GetFloatArrayElements(JNI_carrierFrequencyOffset, nullptr);
	if (!carrierFrequencyOffset)
		goto carrierFrequencyOffsetFail;
	operationMode = env->GetIntArrayElements(JNI_operationMode, nullptr);
	if (!operationMode)
		goto operationModeFail;
	callSign = env->GetByteArrayElements(JNI_callSign, nullptr);
	if (!callSign)
		goto callSignFail;

	decoder->staged(
		reinterpret_cast<float *>(carrierFrequencyOffset),
		reinterpret_cast<int32_t *>(operationMode),
		reinterpret_cast<uint8_t *>(callSign));

	env->ReleaseByteArrayElements(JNI_callSign, callSign, 0);
	callSignFail:
	env->ReleaseIntArrayElements(JNI_operationMode, operationMode, 0);
	operationModeFail:
	env->ReleaseFloatArrayElements(JNI_carrierFrequencyOffset, carrierFrequencyOffset, 0);
	carrierFrequencyOffsetFail:;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aicodix_rattlegram_SignalMonitor_feedDecoder(
	JNIEnv *env,
	jobject instance,
	jshortArray JNI_audioBuffer,
	jint sampleCount,
	jint channelSelect) {
	DecoderInterface *decoder = getDecoder(env, instance);

	jboolean status = false;

	if (!decoder)
		return status;

	jshort *audioBuffer;
	audioBuffer = env->GetShortArrayElements(JNI_audioBuffer, nullptr);
	if (!audioBuffer)
		goto audioBufferFail;

	status = decoder->feed(
		reinterpret_cast<int16_t *>(audioBuffer),
		sampleCount, channelSelect);

	env->ReleaseShortArrayElements(JNI_audioBuffer, audioBuffer, JNI_ABORT);
	audioBufferFail:

	return status;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aicodix_rattlegram_SignalMonitor_processDecoder(
	JNIEnv *env,
	jobject instance) {
	DecoderInterface *decoder = getDecoder(env, instance);

	if (!decoder)
		return STATUS_HEAP;

	return decoder->process();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aicodix_rattlegram_SignalMonitor_spectrumDecoder(
	JNIEnv *env,
	jobject instance,
	jintArray JNI_spectrumPixels,
	jintArray JNI_spectrogramPixels,
	jint spectrumTint) {
	DecoderInterface *decoder = getDecoder(env, instance);

	if (!decoder)
		return;

	jint *spectrumPixels, *spectrogramPixels;
	spectrumPixels = env->GetIntArrayElements(JNI_spectrumPixels, nullptr);
	if (!spectrumPixels)
		goto spectrumFail;
	spectrogramPixels = env->GetIntArrayElements(JNI_spectrogramPixels, nullptr);
	if (!spectrogramPixels)
		goto spectrogramFail;

	decoder->spectrum(
		reinterpret_cast<uint32_t *>(spectrumPixels),
		reinterpret_cast<uint32_t *>(spectrogramPixels),
		spectrumTint);

	env->ReleaseIntArrayElements(JNI_spectrogramPixels, spectrogramPixels, 0);
	spectrogramFail:
	env->ReleaseIntArrayElements(JNI_spectrumPixels, spectrumPixels, 0);
	spectrumFail:;
}

