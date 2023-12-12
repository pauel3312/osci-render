/*
  ==============================================================================

    This file contains the basic framework code for a JUCE plugin processor.

  ==============================================================================
*/

#include "PluginProcessor.h"
#include "PluginEditor.h"
#include "parser/FileParser.h"
#include "parser/FrameProducer.h"
#include "audio/RotateEffect.h"
#include "audio/VectorCancellingEffect.h"
#include "audio/DistortEffect.h"
#include "audio/SmoothEffect.h"
#include "audio/BitCrushEffect.h"
#include "audio/BulgeEffect.h"
#include "audio/LuaEffect.h"
#include "audio/EffectParameter.h"

//==============================================================================
OscirenderAudioProcessor::OscirenderAudioProcessor()
#ifndef JucePlugin_PreferredChannelConfigurations
     : AudioProcessor (BusesProperties()
                     #if ! JucePlugin_IsMidiEffect
                      #if ! JucePlugin_IsSynth
                       .withInput  ("Input",  juce::AudioChannelSet::stereo(), true)
                      #endif
                       .withOutput ("Output", juce::AudioChannelSet::stereo(), true)
                     #endif
                       )
#endif
    {
    // locking isn't necessary here because we are in the constructor

    toggleableEffects.push_back(std::make_shared<Effect>(
        std::make_shared<BitCrushEffect>(),
        new EffectParameter("Bit Crush", "bitCrush", VERSION_HINT, 0.0, 0.0, 1.0),
        "bitCrush"
    ));
    toggleableEffects.push_back(std::make_shared<Effect>(
        std::make_shared<BulgeEffect>(),
        new EffectParameter("Bulge", "bulge", VERSION_HINT, 0.0, 0.0, 1.0),
        "bulge"
    ));
    toggleableEffects.push_back(std::make_shared<Effect>(
        std::make_shared<RotateEffect>(),
        new EffectParameter("2D Rotate", "2DRotateSpeed", VERSION_HINT, 0.0, 0.0, 1.0),
        "2DRotate"
    ));
    toggleableEffects.push_back(std::make_shared<Effect>(
        std::make_shared<VectorCancellingEffect>(),
        new EffectParameter("Vector Cancelling", "vectorCancelling", VERSION_HINT, 0.0, 0.0, 1.0),
        "vectorCancelling"
    ));
    toggleableEffects.push_back(std::make_shared<Effect>(
        std::make_shared<DistortEffect>(false),
        new EffectParameter("Distort X", "distortX", VERSION_HINT, 0.0, 0.0, 1.0),
        "distortX"
    ));
    toggleableEffects.push_back(std::make_shared<Effect>(
        std::make_shared<DistortEffect>(true),
        new EffectParameter("Distort Y", "distortY", VERSION_HINT, 0.0, 0.0, 1.0),
        "distortY"
    ));
    toggleableEffects.push_back(std::make_shared<Effect>(
        [this](int index, Vector2 input, const std::vector<double>& values, double sampleRate) {
            input.x += values[0];
            input.y += values[1];
            return input;
        }, std::vector<EffectParameter*>{new EffectParameter("Translate X", "translateX", VERSION_HINT, 0.0, -1.0, 1.0), new EffectParameter("Translate Y", "translateY", VERSION_HINT, 0.0, -1.0, 1.0)},
        "translate"
    ));
    toggleableEffects.push_back(std::make_shared<Effect>(
        std::make_shared<SmoothEffect>(),
        new EffectParameter("Smoothing", "smoothing", VERSION_HINT, 0.0, 0.0, 1.0),
        "smoothing"
    ));
    toggleableEffects.push_back(std::make_shared<Effect>(
        wobbleEffect,
        new EffectParameter("Wobble", "wobble", VERSION_HINT, 0.0, 0.0, 1.0),
        "wobble"
    ));
    toggleableEffects.push_back(std::make_shared<Effect>(
        delayEffect,
        std::vector<EffectParameter*>{new EffectParameter("Delay Decay", "delayDecay", VERSION_HINT, 0.0, 0.0, 1.0), new EffectParameter("Delay Length", "delayLength", VERSION_HINT, 0.5, 0.0, 1.0)},
        "delay"
    ));
    toggleableEffects.push_back(std::make_shared<Effect>(
        perspectiveEffect,
        std::vector<EffectParameter*>{
            new EffectParameter("3D Perspective", "perspectiveStrength", VERSION_HINT, 0.0, 0.0, 1.0),
            new EffectParameter("Depth (z)", "perspectiveZPos", VERSION_HINT, 0.1, 0.0, 1.0),
            new EffectParameter("Rotate Speed", "perspectiveRotateSpeed", VERSION_HINT, 0.0, -1.0, 1.0),
            new EffectParameter("Rotate X", "perspectiveRotateX", VERSION_HINT, 1.0, -1.0, 1.0),
            new EffectParameter("Rotate Y", "perspectiveRotateY", VERSION_HINT, 1.0, -1.0, 1.0),
            new EffectParameter("Rotate Z", "perspectiveRotateZ", VERSION_HINT, 0.0, -1.0, 1.0),
        },
        "perspective"
    ));
    toggleableEffects.push_back(traceMax);
    toggleableEffects.push_back(traceMin);

    for (int i = 0; i < toggleableEffects.size(); i++) {
        auto effect = toggleableEffects[i];
        effect->markEnableable(false);
        addParameter(effect->enabled);
        effect->enabled->setValueNotifyingHost(false);
        effect->setPrecedence(i);
    }

    permanentEffects.push_back(frequencyEffect);
    permanentEffects.push_back(volumeEffect);
    permanentEffects.push_back(thresholdEffect);
    permanentEffects.push_back(rotateSpeed);
    permanentEffects.push_back(rotateX);
    permanentEffects.push_back(rotateY);
    permanentEffects.push_back(rotateZ);
    permanentEffects.push_back(focalLength);

    for (int i = 0; i < 26; i++) {
        addLuaSlider();
    }

    for (auto& effect : luaEffects) {
        effect->addListener(0, this);
    }

    allEffects = toggleableEffects;
    allEffects.insert(allEffects.end(), permanentEffects.begin(), permanentEffects.end());
    allEffects.insert(allEffects.end(), luaEffects.begin(), luaEffects.end());

    for (auto effect : allEffects) {
        for (auto effectParameter : effect->parameters) {
            auto parameters = effectParameter->getParameters();
            for (auto parameter : parameters) {
                addParameter(parameter);
            }
        }
    }

    booleanParameters.push_back(fixedRotateX);
    booleanParameters.push_back(fixedRotateY);
    booleanParameters.push_back(fixedRotateZ);
    booleanParameters.push_back(perspectiveEffect->fixedRotateX);
    booleanParameters.push_back(perspectiveEffect->fixedRotateY);
    booleanParameters.push_back(perspectiveEffect->fixedRotateZ);
    booleanParameters.push_back(midiEnabled);

    for (auto parameter : booleanParameters) {
        addParameter(parameter);
    }

    addParameter(attackTime);
    addParameter(attackLevel);
    addParameter(attackShape);
    addParameter(decayTime);
    addParameter(decayShape);
    addParameter(sustainLevel);
    addParameter(releaseTime);
    addParameter(releaseShape);

    for (int i = 0; i < 4; i++) {
        synth.addVoice(new ShapeVoice(*this));
    }
        
    synth.addSound(defaultSound);
}

OscirenderAudioProcessor::~OscirenderAudioProcessor() {
    for (auto& effect : luaEffects) {
        effect->removeListener(0, this);
    }
}

const juce::String OscirenderAudioProcessor::getName() const {
    return JucePlugin_Name;
}

bool OscirenderAudioProcessor::acceptsMidi() const {
   #if JucePlugin_WantsMidiInput
    return true;
   #else
    return false;
   #endif
}

bool OscirenderAudioProcessor::producesMidi() const {
   #if JucePlugin_ProducesMidiOutput
    return true;
   #else
    return false;
   #endif
}

bool OscirenderAudioProcessor::isMidiEffect() const {
   #if JucePlugin_IsMidiEffect
    return true;
   #else
    return false;
   #endif
}

double OscirenderAudioProcessor::getTailLengthSeconds() const {
    return 0.0;
}

int OscirenderAudioProcessor::getNumPrograms() {
    return 1;   // NB: some hosts don't cope very well if you tell them there are 0 programs,
                // so this should be at least 1, even if you're not really implementing programs.
}

int OscirenderAudioProcessor::getCurrentProgram() {
    return 0;
}

void OscirenderAudioProcessor::setCurrentProgram(int index) {
}

const juce::String OscirenderAudioProcessor::getProgramName(int index) {
    return {};
}

void OscirenderAudioProcessor::changeProgramName(int index, const juce::String& newName) {}

void OscirenderAudioProcessor::prepareToPlay(double sampleRate, int samplesPerBlock) {
	currentSampleRate = sampleRate;
    pitchDetector.setSampleRate(sampleRate);
    synth.setCurrentPlaybackSampleRate(sampleRate);
}

void OscirenderAudioProcessor::releaseResources() {
    // When playback stops, you can use this as an opportunity to free up any
    // spare memory, etc.
}

#ifndef JucePlugin_PreferredChannelConfigurations
bool OscirenderAudioProcessor::isBusesLayoutSupported (const BusesLayout& layouts) const
{
  #if JucePlugin_IsMidiEffect
    juce::ignoreUnused (layouts);
    return true;
  #else
    // This is the place where you check if the layout is supported.
    // In this template code we only support mono or stereo.
    // Some plugin hosts, such as certain GarageBand versions, will only
    // load plugins that support stereo bus layouts.
    if (layouts.getMainOutputChannelSet() != juce::AudioChannelSet::mono()
     && layouts.getMainOutputChannelSet() != juce::AudioChannelSet::stereo())
        return false;

    // This checks if the input layout matches the output layout
   #if ! JucePlugin_IsSynth
    if (layouts.getMainOutputChannelSet() != layouts.getMainInputChannelSet())
        return false;
   #endif

    return true;
  #endif
}
#endif

// effectsLock should be held when calling this
void OscirenderAudioProcessor::addLuaSlider() {
    juce::String sliderName = "";

    int sliderNum = luaEffects.size() + 1;
    while (sliderNum > 0) {
        int mod = (sliderNum - 1) % 26;
        sliderName = (char)(mod + 'A') + sliderName;
        sliderNum = (sliderNum - mod) / 26;
    }

    luaEffects.push_back(std::make_shared<Effect>(
        std::make_shared<LuaEffect>(sliderName, *this),
        new EffectParameter("Lua " + sliderName, "lua" + sliderName, VERSION_HINT, 0.0, 0.0, 1.0, 0.001, false),
        "lua" + sliderName
    ));

    auto& effect = luaEffects.back();
    effect->parameters[0]->disableLfo();
}

// effectsLock should be held when calling this
void OscirenderAudioProcessor::updateLuaValues() {
    for (auto& effect : luaEffects) {
        effect->apply();
	}
}

// parsersLock should be held when calling this
void OscirenderAudioProcessor::updateObjValues() {
    focalLength->apply();
    rotateX->apply();
    rotateY->apply();
    rotateZ->apply();
    rotateSpeed->apply();
}

// effectsLock should be held when calling this
std::shared_ptr<Effect> OscirenderAudioProcessor::getEffect(juce::String id) {
    for (auto& effect : allEffects) {
        if (effect->getId() == id) {
            return effect;
        }
    }
    return nullptr;
}

// effectsLock should be held when calling this
BooleanParameter* OscirenderAudioProcessor::getBooleanParameter(juce::String id) {
    for (auto& parameter : booleanParameters) {
        if (parameter->paramID == id) {
            return parameter;
        }
    }
    return nullptr;
}

// effectsLock MUST be held when calling this
void OscirenderAudioProcessor::updateEffectPrecedence() {
    auto sortFunc = [](std::shared_ptr<Effect> a, std::shared_ptr<Effect> b) {
        return a->getPrecedence() < b->getPrecedence();
    };
    std::sort(toggleableEffects.begin(), toggleableEffects.end(), sortFunc);
}

// parsersLock AND effectsLock must be locked before calling this function
void OscirenderAudioProcessor::updateFileBlock(int index, std::shared_ptr<juce::MemoryBlock> block) {
    if (index < 0 || index >= fileBlocks.size()) {
		return;
	}
	fileBlocks[index] = block;
	openFile(index);
}

// parsersLock AND effectsLock must be locked before calling this function
void OscirenderAudioProcessor::addFile(juce::File file) {
    fileBlocks.push_back(std::make_shared<juce::MemoryBlock>());
    fileNames.push_back(file.getFileName());
	parsers.push_back(std::make_shared<FileParser>());
    sounds.push_back(new ShapeSound(parsers.back()));
    file.createInputStream()->readIntoMemoryBlock(*fileBlocks.back());

    openFile(fileBlocks.size() - 1);
}

// parsersLock AND effectsLock must be locked before calling this function
void OscirenderAudioProcessor::addFile(juce::String fileName, const char* data, const int size) {
    fileBlocks.push_back(std::make_shared<juce::MemoryBlock>());
    fileNames.push_back(fileName);
    parsers.push_back(std::make_shared<FileParser>());
    sounds.push_back(new ShapeSound(parsers.back()));
    fileBlocks.back()->append(data, size);

    openFile(fileBlocks.size() - 1);
}

// parsersLock AND effectsLock must be locked before calling this function
void OscirenderAudioProcessor::addFile(juce::String fileName, std::shared_ptr<juce::MemoryBlock> data) {
    fileBlocks.push_back(data);
    fileNames.push_back(fileName);
    parsers.push_back(std::make_shared<FileParser>());
    sounds.push_back(new ShapeSound(parsers.back()));

    openFile(fileBlocks.size() - 1);
}

// parsersLock AND effectsLock must be locked before calling this function
void OscirenderAudioProcessor::removeFile(int index) {
	if (index < 0 || index >= fileBlocks.size()) {
		return;
	}
    fileBlocks.erase(fileBlocks.begin() + index);
    fileNames.erase(fileNames.begin() + index);
    parsers.erase(parsers.begin() + index);
    sounds.erase(sounds.begin() + index);
    auto newFileIndex = index;
    if (newFileIndex >= fileBlocks.size()) {
        newFileIndex = fileBlocks.size() - 1;
    }
    changeCurrentFile(newFileIndex);
}

int OscirenderAudioProcessor::numFiles() {
    return fileBlocks.size();
}

// used for opening NEW files. Should be the default way of opening files as
// it will reparse any existing files, so it is safer.
// parsersLock AND effectsLock must be locked before calling this function
void OscirenderAudioProcessor::openFile(int index) {
	if (index < 0 || index >= fileBlocks.size()) {
		return;
	}
    juce::SpinLock::ScopedLockType lock(fontLock);
    parsers[index]->parse(fileNames[index].fromLastOccurrenceOf(".", true, false), std::make_unique<juce::MemoryInputStream>(*fileBlocks[index], false), font);
    changeCurrentFile(index);
}

// used ONLY for changing the current file to an EXISTING file.
// much faster than openFile(int index) because it doesn't reparse any files.
// parsersLock AND effectsLock must be locked before calling this function
void OscirenderAudioProcessor::changeCurrentFile(int index) {
    if (index == -1) {
        currentFile = -1;
        changeSound(defaultSound);
    }
	if (index < 0 || index >= fileBlocks.size()) {
		return;
	}
    currentFile = index;
    updateLuaValues();
    updateObjValues();
    changeSound(sounds[index]);
}

void OscirenderAudioProcessor::changeSound(ShapeSound::Ptr sound) {
    if (!objectServerRendering || sound == objectServerSound) {
        synth.clearSounds();
        synth.addSound(sound);
        for (int i = 0; i < synth.getNumVoices(); i++) {
            auto voice = dynamic_cast<ShapeVoice*>(synth.getVoice(i));
            voice->updateSound(sound.get());
        }
    }
}

int OscirenderAudioProcessor::getCurrentFileIndex() {
    return currentFile;
}

std::shared_ptr<FileParser> OscirenderAudioProcessor::getCurrentFileParser() {
    return parsers[currentFile];
}

juce::String OscirenderAudioProcessor::getCurrentFileName() {
    if (objectServerRendering || currentFile == -1) {
		return "";
    } else {
        return fileNames[currentFile];
    }
}

juce::String OscirenderAudioProcessor::getFileName(int index) {
    return fileNames[index];
}

std::shared_ptr<juce::MemoryBlock> OscirenderAudioProcessor::getFileBlock(int index) {
    return fileBlocks[index];
}

void OscirenderAudioProcessor::setObjectServerRendering(bool enabled) {
    {
        juce::SpinLock::ScopedLockType lock1(parsersLock);

        objectServerRendering = enabled;
        if (enabled) {
            changeSound(objectServerSound);
        } else {
            changeCurrentFile(currentFile);
        }
    }

    {
        juce::MessageManagerLock lock;
        fileChangeBroadcaster.sendChangeMessage();
    }
}

void OscirenderAudioProcessor::processBlock(juce::AudioBuffer<float>& buffer, juce::MidiBuffer& midiMessages) {
    juce::ScopedNoDenormals noDenormals;
    auto totalNumInputChannels  = getTotalNumInputChannels();
    auto totalNumOutputChannels = getTotalNumOutputChannels();

    buffer.clear();
    bool usingMidi = midiEnabled->getBoolValue();
    if (!usingMidi) {
        midiMessages.clear();
    }
    
    // if midi enabled has changed state
    if (prevMidiEnabled != usingMidi) {
        for (int i = 1; i <= 16; i++) {
            midiMessages.addEvent(juce::MidiMessage::allNotesOff(i), i);
        }
    }
    
    // if midi has just been disabled
    if (prevMidiEnabled && !usingMidi) {
        midiMessages.addEvent(juce::MidiMessage::noteOn(1, 60, 1.0f), 17);
    }
    
    prevMidiEnabled = usingMidi;

    const double EPSILON = 0.00001;
    
    
    if (volume > EPSILON) {
        juce::SpinLock::ScopedLockType lock1(parsersLock);
        juce::SpinLock::ScopedLockType lock2(effectsLock);
        synth.renderNextBlock(buffer, midiMessages, 0, buffer.getNumSamples());
    }
    midiMessages.clear();
    
    auto* channelData = buffer.getArrayOfWritePointers();
    
	for (auto sample = 0; sample < buffer.getNumSamples(); ++sample) {
        Vector2 channels = {buffer.getSample(0, sample), buffer.getSample(1, sample)};

        {
            juce::SpinLock::ScopedLockType lock1(parsersLock);
            juce::SpinLock::ScopedLockType lock2(effectsLock);
            if (volume > EPSILON) {
                for (auto& effect : toggleableEffects) {
                    if (effect->enabled->getValue()) {
                        channels = effect->apply(sample, channels);
                    }
                }
            }
            for (auto& effect : permanentEffects) {
                channels = effect->apply(sample, channels);
            }
        }

		double x = channels.x;
		double y = channels.y;

        x *= volume;
        y *= volume;

        // clip
        x = juce::jmax(-threshold, juce::jmin(threshold.load(), x));
        y = juce::jmax(-threshold, juce::jmin(threshold.load(), y));
        
        if (totalNumOutputChannels >= 2) {
			channelData[0][sample] = x;
			channelData[1][sample] = y;
		} else if (totalNumOutputChannels == 1) {
            channelData[0][sample] = x;
        }

        {
            juce::SpinLock::ScopedLockType scope(consumerLock);
            for (auto consumer : consumers) {
                consumer->write(x);
                consumer->write(y);
                consumer->notifyIfFull();
            }
        }
	}
}

//==============================================================================
bool OscirenderAudioProcessor::hasEditor() const {
    return true; // (change this to false if you choose to not supply an editor)
}

juce::AudioProcessorEditor* OscirenderAudioProcessor::createEditor() {
    auto editor = new OscirenderAudioProcessorEditor(*this);
    return editor;
}

//==============================================================================
void OscirenderAudioProcessor::getStateInformation(juce::MemoryBlock& destData) {
    juce::SpinLock::ScopedLockType lock1(parsersLock);
    juce::SpinLock::ScopedLockType lock2(effectsLock);

    std::unique_ptr<juce::XmlElement> xml = std::make_unique<juce::XmlElement>("project");
    xml->setAttribute("version", ProjectInfo::versionString);
    auto effectsXml = xml->createNewChildElement("effects");
    for (auto effect : allEffects) {
        effect->save(effectsXml->createNewChildElement("effect"));
    }

    auto booleanParametersXml = xml->createNewChildElement("booleanParameters");
    for (auto parameter : booleanParameters) {
        auto parameterXml = booleanParametersXml->createNewChildElement("parameter");
        parameter->save(parameterXml);
    }

    auto perspectiveFunction = xml->createNewChildElement("perspectiveFunction");
    perspectiveFunction->addTextElement(juce::Base64::toBase64(perspectiveEffect->getCode()));

    auto fontXml = xml->createNewChildElement("font");
    fontXml->setAttribute("family", font.getTypefaceName());
    fontXml->setAttribute("bold", font.isBold());
    fontXml->setAttribute("italic", font.isItalic());

    auto filesXml = xml->createNewChildElement("files");
    
    for (int i = 0; i < fileBlocks.size(); i++) {
        auto fileXml = filesXml->createNewChildElement("file");
        fileXml->setAttribute("name", fileNames[i]);
        auto fileString = juce::MemoryInputStream(*fileBlocks[i], false).readEntireStreamAsString();
        fileXml->addTextElement(juce::Base64::toBase64(fileString));
    }
    xml->setAttribute("currentFile", currentFile);

    copyXmlToBinary(*xml, destData);
}

void OscirenderAudioProcessor::setStateInformation(const void* data, int sizeInBytes) {
    std::unique_ptr<juce::XmlElement> xml;

    const uint32_t magicXmlNumber = 0x21324356;
    if (sizeInBytes > 8 && juce::ByteOrder::littleEndianInt(data) == magicXmlNumber) {
        // this is a binary xml format
        xml = getXmlFromBinary(data, sizeInBytes);
    } else {
        // this is a text xml format
        xml = juce::XmlDocument::parse(juce::String((const char*)data, sizeInBytes));
    }

    if (xml.get() != nullptr && xml->hasTagName("project")) {
        auto versionXml = xml->getChildByName("version");
        if (versionXml != nullptr && versionXml->getAllSubText().startsWith("v1.")) {
            openLegacyProject(xml.get());
            return;
        }

        juce::SpinLock::ScopedLockType lock1(parsersLock);
        juce::SpinLock::ScopedLockType lock2(effectsLock);

        auto effectsXml = xml->getChildByName("effects");
        if (effectsXml != nullptr) {
            for (auto effectXml : effectsXml->getChildIterator()) {
                auto effect = getEffect(effectXml->getStringAttribute("id"));
                if (effect != nullptr) {
                    effect->load(effectXml);
                }
            }
        }
        updateEffectPrecedence();

        auto booleanParametersXml = xml->getChildByName("booleanParameters");
        if (booleanParametersXml != nullptr) {
            for (auto parameterXml : booleanParametersXml->getChildIterator()) {
                auto parameter = getBooleanParameter(parameterXml->getStringAttribute("id"));
                if (parameter != nullptr) {
                    parameter->load(parameterXml);
                }
            }
        }

        auto perspectiveFunction = xml->getChildByName("perspectiveFunction");
        if (perspectiveFunction != nullptr) {
            auto stream = juce::MemoryOutputStream();
            juce::Base64::convertFromBase64(stream, perspectiveFunction->getAllSubText());
            perspectiveEffect->updateCode(stream.toString());
        }

        auto fontXml = xml->getChildByName("font");
        if (fontXml != nullptr) {
            auto family = fontXml->getStringAttribute("family");
            auto bold = fontXml->getBoolAttribute("bold");
            auto italic = fontXml->getBoolAttribute("italic");
            juce::SpinLock::ScopedLockType lock(fontLock);
            font = juce::Font(family, 1.0, (bold ? juce::Font::bold : 0) | (italic ? juce::Font::italic : 0));
        }

        // close all files
        auto numFiles = fileBlocks.size();
        for (int i = 0; i < numFiles; i++) {
            removeFile(0);
        }

        auto filesXml = xml->getChildByName("files");
        if (filesXml != nullptr) {
            for (auto fileXml : filesXml->getChildIterator()) {
                auto fileName = fileXml->getStringAttribute("name");
                auto stream = juce::MemoryOutputStream();
                juce::Base64::convertFromBase64(stream, fileXml->getAllSubText());
                auto fileBlock = std::make_shared<juce::MemoryBlock>(stream.getData(), stream.getDataSize());
                addFile(fileName, fileBlock);
            }
        }
        changeCurrentFile(xml->getIntAttribute("currentFile", -1));
        broadcaster.sendChangeMessage();
        prevMidiEnabled = !midiEnabled->getBoolValue();
    }
}

std::shared_ptr<BufferConsumer> OscirenderAudioProcessor::consumerRegister(std::vector<float>& buffer) {
    std::shared_ptr<BufferConsumer> consumer = std::make_shared<BufferConsumer>(buffer);
    juce::SpinLock::ScopedLockType scope(consumerLock);
    consumers.push_back(consumer);
    
    return consumer;
}

void OscirenderAudioProcessor::consumerRead(std::shared_ptr<BufferConsumer> consumer) {
    consumer->waitUntilFull();
    juce::SpinLock::ScopedLockType scope(consumerLock);
    consumers.erase(std::remove(consumers.begin(), consumers.end(), consumer), consumers.end());
}

void OscirenderAudioProcessor::consumerStop(std::shared_ptr<BufferConsumer> consumer) {
    if (consumer != nullptr) {
        juce::SpinLock::ScopedLockType scope(consumerLock);
        consumer->forceNotify();
    }
}

void OscirenderAudioProcessor::parameterValueChanged(int parameterIndex, float newValue) {
    // call apply on lua effects
    for (auto& effect : luaEffects) {
        if (parameterIndex == effect->parameters[0]->getParameterIndex()) {
            effect->apply();
            return;
        }
    }
}

void OscirenderAudioProcessor::parameterGestureChanged(int parameterIndex, bool gestureIsStarting) {}

void updateIfApproxEqual(FloatParameter* parameter, float newValue) {
    if (std::abs(parameter->getValueUnnormalised() - newValue) > 0.0001) {
        parameter->setUnnormalisedValueNotifyingHost(newValue);
    }
}

void OscirenderAudioProcessor::envelopeChanged(EnvelopeComponent* changedEnvelope) {
    Env env = changedEnvelope->getEnv();
    std::vector<double> levels = env.getLevels();
    std::vector<double> times = env.getTimes();
    EnvCurveList curves = env.getCurves();

    if (levels.size() == 4 && times.size() == 3 && curves.size() == 3) {
        {
            juce::SpinLock::ScopedLockType lock(effectsLock);
            this->adsrEnv = env;
        }
        updateIfApproxEqual(attackTime, times[0]);
        updateIfApproxEqual(attackLevel, levels[1]);
        updateIfApproxEqual(attackShape, curves[0].getCurve());
        updateIfApproxEqual(decayTime, times[1]);
        updateIfApproxEqual(sustainLevel, levels[2]);
        updateIfApproxEqual(decayShape, curves[1].getCurve());
        updateIfApproxEqual(releaseTime, times[2]);
        updateIfApproxEqual(releaseShape, curves[2].getCurve());
        DBG("adsr changed");
    }
}


//==============================================================================
// This creates new instances of the plugin..
juce::AudioProcessor* JUCE_CALLTYPE createPluginFilter()
{
    return new OscirenderAudioProcessor();
}
