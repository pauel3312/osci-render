package sh.ball.gui.controller;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.collections.FXCollections;
import javafx.scene.control.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.SVGPath;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import sh.ball.audio.*;
import sh.ball.audio.effect.*;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import javax.sound.midi.ShortMessage;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.xml.sax.SAXException;
import sh.ball.audio.effect.EffectType;
import sh.ball.audio.engine.AudioDevice;
import sh.ball.audio.engine.ConglomerateAudioEngine;
import sh.ball.audio.midi.MidiCommunicator;
import sh.ball.audio.midi.MidiListener;
import sh.ball.audio.midi.MidiNote;
import sh.ball.engine.Vector3;
import sh.ball.parser.obj.ObjFrameSettings;
import sh.ball.parser.obj.ObjSettingsFactory;
import sh.ball.parser.obj.ObjParser;
import sh.ball.parser.ParserFactory;
import sh.ball.shapes.Shape;
import sh.ball.shapes.Vector2;

import static sh.ball.math.Math.tryParse;

public class MainController implements Initializable, FrequencyListener, MidiListener {

  private String openProjectPath;

  // audio
  private static final double MAX_FREQUENCY = 12000;
  private final ShapeAudioPlayer audioPlayer;
  private final RotateEffect rotateEffect;
  private final TranslateEffect translateEffect;
  private final DoubleProperty frequency;
  private final AudioDevice defaultDevice;
  private int sampleRate;
  private FrequencyAnalyser<List<Shape>> analyser;
  private boolean recording = false;
  private Timeline recordingTimeline;

  // midi
  private final Map<Integer, SVGPath> CCMap = new HashMap<>();
  private Paint armedMidiPaint;
  private SVGPath armedMidi;
  private Map<SVGPath, Slider> midiButtonMap;

  // frames
  private static final InputStream DEFAULT_OBJ = MainController.class.getResourceAsStream("/models/cube.obj");
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private List<byte[]> openFiles = new ArrayList<>();
  private List<String> frameSourcePaths = new ArrayList<>();
  private List<FrameSource<List<Shape>>> frameSources = new ArrayList<>();
  private FrameProducer<List<Shape>> producer;
  private int currentFrameSource;

  // frame playback (code by DJ_Level_3)
  private static final int MAX_FRAME_RATE = 120;
  private static final int MIN_FRAME_RATE = 1;
  private boolean framesPlaying = false; // default to not playing
  private int frameRate = 10; // default to 10 frames per second

  // javafx
  private final FileChooser osciFileChooser = new FileChooser();
  private final FileChooser wavFileChooser = new FileChooser();
  private final FileChooser renderFileChooser = new FileChooser();
  private final DirectoryChooser folderChooser = new DirectoryChooser();
  private Stage stage;

  @FXML
  private EffectsController effectsController;
  @FXML
  private ObjController objController;
  @FXML
  private Label frequencyLabel;
  @FXML
  private Button chooseFileButton;
  @FXML
  private Button chooseFolderButton;
  @FXML
  private Label fileLabel;
  @FXML
  private Label jkLabel;
  @FXML
  private Button recordButton;
  @FXML
  private Label recordLabel;
  @FXML
  private TextField recordTextField;
  @FXML
  private CheckBox recordCheckBox;
  @FXML
  private Label recordLengthLabel;
  @FXML
  private TextField translationXTextField;
  @FXML
  private TextField translationYTextField;
  @FXML
  private Slider frequencySlider;
  @FXML
  private SVGPath frequencyMidi;
  @FXML
  private Slider rotateSpeedSlider;
  @FXML
  private SVGPath rotateSpeedMidi;
  @FXML
  private Slider translationSpeedSlider;
  @FXML
  private SVGPath translationSpeedMidi;
  @FXML
  private Slider volumeSlider;
  @FXML
  private SVGPath volumeMidi;
  @FXML
  private TitledPane objTitledPane;
  @FXML
  private Slider octaveSlider;
  @FXML
  private SVGPath octaveMidi;
  @FXML
  private Slider visibilitySlider;
  @FXML
  private SVGPath visibilityMidi;
  @FXML
  private ComboBox<AudioDevice> deviceComboBox;
  @FXML
  private MenuItem openProjectMenuItem;
  @FXML
  private MenuItem saveProjectMenuItem;
  @FXML
  private MenuItem saveAsProjectMenuItem;

  public MainController() throws Exception {
    MidiCommunicator midiCommunicator = new MidiCommunicator();
    midiCommunicator.addListener(this);
    new Thread(midiCommunicator).start();

    this.audioPlayer = new ShapeAudioPlayer(ConglomerateAudioEngine::new, midiCommunicator);

    // Clone DEFAULT_OBJ InputStream using a ByteArrayOutputStream
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    assert DEFAULT_OBJ != null;
    DEFAULT_OBJ.transferTo(baos);
    InputStream objClone = new ByteArrayInputStream(baos.toByteArray());
    FrameSource<List<Shape>> frames = new ObjParser(objClone).parse();

    openFiles.add(baos.toByteArray());
    frameSources.add(frames);
    frameSourcePaths.add("cube.obj");
    currentFrameSource = 0;
    this.producer = new FrameProducer<>(audioPlayer, frames);
    this.defaultDevice = audioPlayer.getDefaultDevice();
    if (defaultDevice == null) {
      throw new RuntimeException("No default audio device found!");
    }
    this.sampleRate = defaultDevice.sampleRate();
    this.rotateEffect = new RotateEffect(sampleRate);
    this.translateEffect = new TranslateEffect(sampleRate);
    this.frequency = new SimpleDoubleProperty(0);
  }

  // initialises midiButtonMap by mapping MIDI logo SVGs to the slider that they
  // control if they are selected.
  private Map<SVGPath, Slider> initializeMidiButtonMap() {
    Map<SVGPath, Slider> midiMap = new HashMap<>();
    midiMap.put(frequencyMidi, frequencySlider);
    midiMap.put(rotateSpeedMidi, rotateSpeedSlider);
    midiMap.put(translationSpeedMidi, translationSpeedSlider);
    midiMap.put(volumeMidi, volumeSlider);
    midiMap.put(octaveMidi, octaveSlider);
    midiMap.put(visibilityMidi, visibilitySlider);
    midiMap.putAll(objController.getMidiButtonMap());
    midiMap.putAll(effectsController.getMidiButtonMap());
    return midiMap;
  }

  // Maps sliders to the functions that they should call whenever their value
  // changes.
  private Map<Slider, Consumer<Double>> initializeSliderMap() {
    return Map.of(
      rotateSpeedSlider, rotateEffect::setSpeed,
      translationSpeedSlider, translateEffect::setSpeed,
      visibilitySlider, audioPlayer::setMainFrequencyScale
    );
  }

  @Override
  public void initialize(URL url, ResourceBundle resourceBundle) {
    // converts the value of frequencySlider to the actual frequency that it represents so that it
    // can increase at an exponential scale.
    frequencySlider.valueProperty().addListener((o, old, f) -> frequency.set(Math.pow(MAX_FREQUENCY, f.doubleValue())));
    frequency.addListener((o, old, f) -> frequencySlider.setValue(Math.log(f.doubleValue()) / Math.log(MAX_FREQUENCY)));
    audioPlayer.setFrequency(frequency);
    // default value is middle C
    frequency.set(MidiNote.MIDDLE_C);
    audioPlayer.setVolume(volumeSlider.valueProperty());

    effectsController.setAudioPlayer(audioPlayer);
    objController.setAudioProducer(producer);

    this.midiButtonMap = initializeMidiButtonMap();

    midiButtonMap.keySet().forEach(midi -> midi.setOnMouseClicked(e -> {
      if (armedMidi == midi) {
        // we are already armed, so we should unarm
        midi.setFill(armedMidiPaint);
        armedMidiPaint = null;
        armedMidi = null;
      } else {
        // not yet armed
        if (armedMidi != null) {
          armedMidi.setFill(armedMidiPaint);
        }
        armedMidiPaint = midi.getFill();
        armedMidi = midi;
        midi.setFill(Color.RED);
      }
    }));

    Map<Slider, Consumer<Double>> sliders = initializeSliderMap();

    for (Slider slider : sliders.keySet()) {
      slider.valueProperty().addListener((source, oldValue, newValue) ->
        sliders.get(slider).accept(newValue.doubleValue())
      );
    }

    translationXTextField.textProperty().addListener(e -> updateTranslation());
    translationYTextField.textProperty().addListener(e -> updateTranslation());

    octaveSlider.valueProperty().addListener((e, old, octave) -> audioPlayer.setOctave(octave.intValue()));

    osciFileChooser.setInitialFileName("project.osci");
    osciFileChooser.getExtensionFilters().add(
      new FileChooser.ExtensionFilter("osci-render files", "*.osci")
    );
    wavFileChooser.setInitialFileName("out.wav");
    wavFileChooser.getExtensionFilters().addAll(
      new FileChooser.ExtensionFilter("WAV Files", "*.wav"),
      new FileChooser.ExtensionFilter("All Files", "*.*")
    );
    // when opening new files, we support .obj, .svg, and .txt
    renderFileChooser.getExtensionFilters().addAll(
      new FileChooser.ExtensionFilter("All Files", "*.*"),
      new FileChooser.ExtensionFilter("Wavefront OBJ Files", "*.obj"),
      new FileChooser.ExtensionFilter("SVG Files", "*.svg"),
      new FileChooser.ExtensionFilter("Text Files", "*.txt")
    );

    saveProjectMenuItem.setOnAction(e -> {
      if (openProjectPath != null) {
        saveProject(openProjectPath);
      } else {
        File file = osciFileChooser.showSaveDialog(stage);
        if (file != null) {
          updateLastVisitedDirectory(new File(file.getParent()));
          saveProject(file.getAbsolutePath());
        }
      }
    });

    saveAsProjectMenuItem.setOnAction(e -> {
      File file = osciFileChooser.showSaveDialog(stage);
      if (file != null) {
        updateLastVisitedDirectory(new File(file.getParent()));
        saveProject(file.getAbsolutePath());
      }
    });

    openProjectMenuItem.setOnAction(e -> {
      File file = osciFileChooser.showOpenDialog(stage);
      if (file != null) {
        updateLastVisitedDirectory(new File(file.getParent()));
        openProject(file.getAbsolutePath());
      }
    });

    chooseFileButton.setOnAction(e -> {
      File file = renderFileChooser.showOpenDialog(stage);
      if (file != null) {
        chooseFile(file);
        updateLastVisitedDirectory(new File(file.getParent()));
      }
    });

    chooseFolderButton.setOnAction(e -> {
      File file = folderChooser.showDialog(stage);
      if (file != null) {
        chooseFile(file);
        updateLastVisitedDirectory(file);
      }
    });

    recordButton.setOnAction(event -> toggleRecord());

    recordCheckBox.selectedProperty().addListener((e, oldVal, newVal) -> {
      recordLengthLabel.setDisable(!newVal);
      recordTextField.setDisable(!newVal);
    });

    objController.updateObjectRotateSpeed();

    audioPlayer.addEffect(EffectType.ROTATE, rotateEffect);
    audioPlayer.addEffect(EffectType.TRANSLATE, translateEffect);

    audioPlayer.setDevice(defaultDevice);
    effectsController.setAudioDevice(defaultDevice);
    List<AudioDevice> devices = audioPlayer.devices();
    deviceComboBox.setItems(FXCollections.observableList(devices));
    deviceComboBox.setValue(defaultDevice);

    executor.submit(producer);
    analyser = new FrequencyAnalyser<>(audioPlayer, 2, sampleRate);
    startFrequencyAnalyser(analyser);
    startAudioPlayerThread();

    deviceComboBox.valueProperty().addListener((options, oldDevice, newDevice) -> {
      if (newDevice != null) {
        switchAudioDevice(newDevice);
      }
    });
  }

  // used when a file is chosen so that the same folder is reopened when a
  // file chooser opens
  private void updateLastVisitedDirectory(File file) {
    String lastVisitedDirectory = file != null ? file.getAbsolutePath() : System.getProperty("user.home");
    File dir = new File(lastVisitedDirectory);
    osciFileChooser.setInitialDirectory(dir);
    wavFileChooser.setInitialDirectory(dir);
    renderFileChooser.setInitialDirectory(dir);
    folderChooser.setInitialDirectory(dir);
  }

  // restarts audioPlayer and FrequencyAnalyser to support new device
  private void switchAudioDevice(AudioDevice device) {
    try {
      audioPlayer.reset();
    } catch (Exception e) {
      e.printStackTrace();
    }
    audioPlayer.setDevice(device);
    effectsController.setAudioDevice(device);
    analyser.stop();
    sampleRate = device.sampleRate();
    analyser = new FrequencyAnalyser<>(audioPlayer, 2, sampleRate);
    startFrequencyAnalyser(analyser);
    effectsController.setFrequencyAnalyser(analyser);
    startAudioPlayerThread();
  }

  // creates a new thread for the audioPlayer and starts it
  private void startAudioPlayerThread() {
    Thread audioPlayerThread = new Thread(audioPlayer);
    audioPlayerThread.setUncaughtExceptionHandler((thread, throwable) -> throwable.printStackTrace());
    audioPlayerThread.start();
  }

  // creates a new thread for the frequency analyser and adds the wobble effect and the controller
  // as listeners of it so that they can get updates as the frequency changes
  private void startFrequencyAnalyser(FrequencyAnalyser<List<Shape>> analyser) {
    analyser.addListener(this);
    effectsController.setFrequencyAnalyser(analyser);
    new Thread(analyser).start();
  }

  // alternates between recording and not recording when called.
  // If it is a non-timed recording, it is saved when this is called and
  // recording is stopped. If it is a time recording, this function will cancel
  // the recording.
  private void toggleRecord() {
    recording = !recording;
    boolean timedRecord = recordCheckBox.isSelected();
    if (recording) {
      // if it is a timed recording then a timeline is scheduled to start and
      // stop recording at the predefined times.
      if (timedRecord) {
        double recordingLength;
        try {
          recordingLength = Double.parseDouble(recordTextField.getText());
        } catch (NumberFormatException e) {
          recordLabel.setText("Please set a valid record length");
          recording = false;
          return;
        }
        recordButton.setText("Cancel");
        KeyFrame kf1 = new KeyFrame(
          Duration.seconds(0),
          e -> audioPlayer.startRecord()
        );
        // save the recording after recordingLength seconds
        KeyFrame kf2 = new KeyFrame(
          Duration.seconds(recordingLength),
          e -> {
            saveRecording();
            recording = false;
          }
        );
        recordingTimeline = new Timeline(kf1, kf2);
        Platform.runLater(recordingTimeline::play);
      } else {
        recordButton.setText("Stop Recording");
        audioPlayer.startRecord();
      }
      recordLabel.setText("Recording...");
    } else if (timedRecord) {
      // cancel the recording
      recordingTimeline.stop();
      recordLabel.setText("");
      recordButton.setText("Record");
      audioPlayer.stopRecord();
    } else {
      saveRecording();
    }
  }

  // Stops recording and opens a fileChooser so that the user can choose a
  // location to save the recording to. If no location is chosen then it will
  // be saved as the current date-time of the machine.
  private void saveRecording() {
    try {
      recordButton.setText("Record");
      AudioInputStream input = audioPlayer.stopRecord();
      File file = wavFileChooser.showSaveDialog(stage);
      SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
      Date date = new Date(System.currentTimeMillis());
      if (file == null) {
        file = new File("out-" + formatter.format(date) + ".wav");
      }
      AudioSystem.write(input, AudioFileFormat.Type.WAVE, file);
      input.close();
      recordLabel.setText("Saved to " + file.getAbsolutePath());
    } catch (IOException e) {
      recordLabel.setText("Error saving file");
      e.printStackTrace();
    }
  }

  // changes the sinusoidal translation of the image rendered
  private void updateTranslation() {
    translateEffect.setTranslation(new Vector2(
      tryParse(translationXTextField.getText()),
      tryParse(translationYTextField.getText())
    ));
  }

  // changes the FrameProducer e.g. could be changing from a 3D object to an
  // SVG. The old FrameProducer is stopped and a new one created and initialised
  // with the same settings that the original had.
  private void changeFrameSource(int index) {
    index = Math.max(0, Math.min(index, frameSources.size() - 1));
    currentFrameSource = index;
    FrameSource<List<Shape>> frames = frameSources.get(index);
    frameSources.forEach(FrameSource::disable);
    frames.enable();

    Object oldSettings = producer.getFrameSettings();
    producer = new FrameProducer<>(audioPlayer, frames);
    objController.setAudioProducer(producer);

    // Apply the same settings that the previous frameSource had
    objController.updateObjectRotateSpeed();
    objController.updateFocalLength();
    if (oldSettings instanceof ObjFrameSettings settings) {
      setObjRotate(settings.baseRotation, settings.currentRotation);
    }
    executor.submit(producer);
    effectsController.restartEffects();

    updateFrameLabels();
    // enable the .obj file settings iff the new frameSource is for a 3D object.
    objTitledPane.setDisable(!ObjParser.isObjFile(frameSourcePaths.get(index)));
  }

  private void updateFiles(List<byte[]> files, List<String> names) throws IOException, ParserConfigurationException, SAXException {
    List<FrameSource<List<Shape>>> newFrameSources = new ArrayList<>();
    List<String> newFrameSourcePaths = new ArrayList<>();
    List<byte[]> newOpenFiles = new ArrayList<>();

    for (int i = 0; i < files.size(); i++) {
      try {
        newFrameSources.add(ParserFactory.getParser(names.get(i), files.get(i)).parse());
        newFrameSourcePaths.add(names.get(i));
        newOpenFiles.add(files.get(i));
      } catch (IOException ignored) {}
    }

    if (newFrameSources.size() > 0) {
      jkLabel.setVisible(newFrameSources.size() > 1);
      framesPlaying = framesPlaying && newFrameSources.size() > 1;
      frameSources.forEach(FrameSource::disable);
      frameSources = newFrameSources;
      frameSourcePaths = newFrameSourcePaths;
      openFiles = newOpenFiles;
      changeFrameSource(0);
    }
  }

  // selects a new file or folder for files to be rendered from
  private void chooseFile(File chosenFile) {
    try {
      if (chosenFile.exists()) {
        List<byte[]> files = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (chosenFile.isDirectory()) {
          File[] fileList = Objects.requireNonNull(chosenFile.listFiles());
          Arrays.sort(fileList);
          for (File file : fileList) {
            files.add(Files.readAllBytes(file.toPath()));
            names.add(file.getName());
          }
        } else {
          files.add(Files.readAllBytes(chosenFile.toPath()));
          names.add(chosenFile.getName());
        }

        updateFiles(files, names);
      }
    } catch (IOException | ParserConfigurationException | SAXException ioException) {
      ioException.printStackTrace();

      // display error to user (for debugging purposes)
      String oldPath = fileLabel.getText();
      // shows the error message and later shows the old path as the file being rendered
      // doesn't change
      KeyFrame kf1 = new KeyFrame(Duration.seconds(0), e -> fileLabel.setText(ioException.getMessage()));
      KeyFrame kf2 = new KeyFrame(Duration.seconds(5), e -> fileLabel.setText(oldPath));
      Timeline timeline = new Timeline(kf1, kf2);
      Platform.runLater(timeline::play);
    }
  }

  // used so that the Controller has access to the stage, allowing it to open
  // file directories etc.
  public void setStage(Stage stage) {
    this.stage = stage;
  }

  // increments and changes the frameSource after pressing 'j'
  public void nextFrameSource() {
    if (frameSources.size() == 1) {
      return;
    }
    int index = currentFrameSource + 1;
    if (index >= frameSources.size()) {
      index = 0;
    }
    changeFrameSource(index);
  }

  // decrements and changes the frameSource after pressing 'k'
  public void previousFrameSource() {
    if (frameSources.size() == 1) {
      return;
    }
    int index = currentFrameSource - 1;
    if (index < 0) {
      index = frameSources.size() - 1;
    }
    changeFrameSource(index);
  }

  // ==================== Start code block by DJ_Level_3 ====================
  //
  //     Quickly written code by DJ_Level_3, a programmer who is not super
  // experienced with Java. It almost definitely could be made better in one
  // way or another, but testing so far shows that the code is stable and
  // doesn't noticeably decrease performance.
  //

  private void updateFrameLabels() {
    if (framesPlaying) {
      fileLabel.setText("Frame rate: " + frameRate);
      jkLabel.setText("Use u and o to decrease and increase the frame rate, or i to stop playback");
    } else {
      fileLabel.setText(frameSourcePaths.get(currentFrameSource));
      jkLabel.setText("Use j and k (or MIDI Program Change) to cycle between files, or i to start playback");
    }
  }

  private void disablePlayback() {
    framesPlaying = false;
  }

  private void enablePlayback() {
    framesPlaying = true;
    doPlayback();
  }

  // toggles frameSource playback after pressing 'i'
  public void togglePlayback() {
    if (frameSources.size() == 1) {
      return;
    }
    if (framesPlaying) {
      disablePlayback();
    } else {
      enablePlayback();
    }
    updateFrameLabels();
  }

  // increments frameRate (up to maximum) after pressing 'u'
  public void increaseFrameRate() {
    updateFrameLabels();
    if (frameRate < MAX_FRAME_RATE) {
      frameRate++;
    } else {
      frameRate = MAX_FRAME_RATE;
    }
  }

  // decrements frameRate (down to minimum) after pressing 'o'
  public void decreaseFrameRate() {
    updateFrameLabels();
    if (frameRate > MIN_FRAME_RATE) {
      frameRate--;
    } else {
      frameRate = MIN_FRAME_RATE;
    }
  }

  // repeatedly swaps frameSource when playback is enabled
  private void doPlayback() {
    if (framesPlaying) {
      KeyFrame now = new KeyFrame(Duration.seconds(0), e -> nextFrameSource());
      KeyFrame next = new KeyFrame(Duration.seconds(1.0 / frameRate), e -> {
        doPlayback();
      });
      Timeline timeline = new Timeline(now, next);
      Platform.runLater(timeline::play);
    } else {
      nextFrameSource();
    }
  }

  // ====================  End code block by DJ_Level_3  ====================

  // determines whether the mouse is being used to rotate a 3D object
  public boolean mouseRotate() {
    return objController.mouseRotate();
  }

  // stops the mouse rotating the 3D object when ESC is pressed or checkbox is
  // unchecked
  public void disableMouseRotate() {
    objController.disableMouseRotate();
  }

  // updates the 3D object base rotation angle
  public void setObjRotate(Vector3 vector) {
    objController.setObjRotate(vector);
  }

  // updates the 3D object base and current rotation angle
  protected void setObjRotate(Vector3 baseRotation, Vector3 currentRotation) {
    objController.setObjRotate(baseRotation, currentRotation);
  }

  @Override
  public void updateFrequency(double leftFrequency, double rightFrequency) {
    Platform.runLater(() ->
      frequencyLabel.setText(String.format("L/R Frequency:\n%d Hz / %d Hz", Math.round(leftFrequency), Math.round(rightFrequency)))
    );
  }

  private void mapMidiCC(int cc, SVGPath midiButton) {
    if (CCMap.containsValue(midiButton)) {
      CCMap.values().remove(midiButton);
    }
    if (CCMap.containsKey(cc)) {
      CCMap.get(cc).setFill(Color.WHITE);
    }
    CCMap.put(cc, midiButton);
    midiButton.setFill(Color.LIME);
  }

  // converts MIDI pressure value into a valid value for a slider
  private double midiPressureToPressure(Slider slider, int midiPressure) {
    double max = slider.getMax();
    double min = slider.getMin();
    double range = max - min;
    return min + (midiPressure / MidiNote.MAX_PRESSURE) * range;
  }

  // handles newly received MIDI messages. For CC messages, this handles
  // whether or not there is a slider that is associated with the CC channel,
  // and the slider's value is updated if so. If there are channels that are
  // looking to be armed, a new association will be created between the CC
  // channel and the slider.
  @Override
  public void sendMidiMessage(ShortMessage message) {
    int command = message.getCommand();

    // the audioPlayer handles all non-CC MIDI messages
    if (command == ShortMessage.CONTROL_CHANGE) {
      int cc = message.getData1();
      int value = message.getData2();

      // if a user has selected a MIDI logo next to a slider, create a mapping
      // between the MIDI channel and the SVG MIDI logo
      if (armedMidi != null) {
        mapMidiCC(cc, armedMidi);
        armedMidiPaint = null;
        armedMidi = null;
      }
      // If there is a slider associated with the MIDI channel, update the value
      // of it
      if (CCMap.containsKey(cc)) {
        Slider slider = midiButtonMap.get(CCMap.get(cc));
        double sliderValue = midiPressureToPressure(slider, value);

        if (slider.isSnapToTicks()) {
          double increment = slider.getMajorTickUnit() / (slider.getMinorTickCount() + 1);
          sliderValue = increment * (Math.round(sliderValue / increment));
        }
        slider.setValue(sliderValue);
      }
    } else if (command == ShortMessage.PROGRAM_CHANGE) {
      // We want to change the file that is currently playing
      Platform.runLater(() -> changeFrameSource(message.getMessage()[1]));
    }
  }

  // must be functions, otherwise they are not initialised
  private List<Slider> otherSliders() {
    List<Slider> sliders = new ArrayList<>(List.of(octaveSlider, frequencySlider, rotateSpeedSlider, translationSpeedSlider,
      volumeSlider, visibilitySlider));
    sliders.addAll(objController.sliders());
    return sliders;
  }
  private List<String> otherLabels() {
    List<String> labels = new ArrayList<>(List.of("octave", "frequency", "rotateSpeed", "translationSpeed", "volume",
      "visibility"));
    labels.addAll(objController.labels());
    return labels;
  }
  private List<Slider> allSliders() {
    List<Slider> sliders = new ArrayList<>(effectsController.sliders());
    sliders.addAll(otherSliders());
    return sliders;
  }
  private List<String> allLabels() {
    List<String> labels = new ArrayList<>(effectsController.labels());
    labels.addAll(otherLabels());
    return labels;
  }

  private void appendSliders(List<Slider> sliders, List<String> labels, Element root, Document document) {
    for (int i = 0; i < sliders.size(); i++) {
      Element sliderElement = document.createElement(labels.get(i));
      sliderElement.appendChild(
        document.createTextNode(sliders.get(i).valueProperty().getValue().toString())
      );
      root.appendChild(sliderElement);
    }
  }

  private void loadSliderValues(List<Slider> sliders, List<String> labels, Element root) {
    for (int i = 0; i < sliders.size(); i++) {
      String value = root.getElementsByTagName(labels.get(i)).item(0).getTextContent();
      sliders.get(i).setValue(Float.parseFloat(value));
    }
  }

  private void saveProject(String projectFileName) {
    try {
      DocumentBuilderFactory documentFactory = DocumentBuilderFactory.newInstance();
      DocumentBuilder documentBuilder = documentFactory.newDocumentBuilder();
      Document document = documentBuilder.newDocument();

      Element root = document.createElement("project");
      document.appendChild(root);

      List<Slider> sliders = allSliders();
      List<String> labels = allLabels();

      Element slidersElement = document.createElement("sliders");
      appendSliders(sliders, labels, slidersElement, document);
      root.appendChild(slidersElement);

      root.appendChild(effectsController.save(document));

      Element midiElement = document.createElement("midi");
      for (Map.Entry<Integer, SVGPath> entry : CCMap.entrySet()) {
        Slider slider = midiButtonMap.get(entry.getValue());
        int index = sliders.indexOf(slider);
        Integer cc = entry.getKey();
        Element midiChannel = document.createElement(labels.get(index));
        midiChannel.appendChild(document.createTextNode(cc.toString()));
        midiElement.appendChild(midiChannel);
      }
      root.appendChild(midiElement);

      Element translationElement = document.createElement("translation");
      Element translationXElement = document.createElement("x");
      translationXElement.appendChild(document.createTextNode(translationXTextField.getText()));
      Element translationYElement = document.createElement("y");
      translationYElement.appendChild(document.createTextNode(translationYTextField.getText()));
      translationElement.appendChild(translationXElement);
      translationElement.appendChild(translationYElement);
      root.appendChild(translationElement);

      root.appendChild(objController.save(document));

      Element filesElement = document.createElement("files");
      for (int i = 0; i < openFiles.size(); i++) {
        Element fileElement = document.createElement("file");
        Element fileNameElement = document.createElement("name");
        fileNameElement.appendChild(document.createTextNode(frameSourcePaths.get(i)));
        Element dataElement = document.createElement("data");
        String encodedData = Base64.getEncoder().encodeToString(openFiles.get(i));
        dataElement.appendChild(document.createTextNode(encodedData));
        fileElement.appendChild(fileNameElement);
        fileElement.appendChild(dataElement);
        filesElement.appendChild(fileElement);
      }
      root.appendChild(filesElement);

      TransformerFactory transformerFactory = TransformerFactory.newInstance();
      Transformer transformer = transformerFactory.newTransformer();
      DOMSource domSource = new DOMSource(document);
      StreamResult streamResult = new StreamResult(new File(projectFileName));

      transformer.transform(domSource, streamResult);
      openProjectPath = projectFileName;
    } catch (ParserConfigurationException | TransformerException e) {
      e.printStackTrace();
    }
  }

  private void resetCCMap() {
    armedMidi = null;
    armedMidiPaint = null;
    CCMap.clear();
    midiButtonMap.keySet().forEach(button -> button.setFill(Color.WHITE));
  }

  private void openProject(String projectFileName) {
    try {
      DocumentBuilderFactory documentFactory = DocumentBuilderFactory.newInstance();
      documentFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      DocumentBuilder documentBuilder = documentFactory.newDocumentBuilder();

      Document document = documentBuilder.parse(new File(projectFileName));
      document.getDocumentElement().normalize();

      List<Slider> sliders = allSliders();
      List<String> labels = allLabels();

      // Disable cycling through frames
      disablePlayback();

      Element root = document.getDocumentElement();
      Element slidersElement = (Element) root.getElementsByTagName("sliders").item(0);
      loadSliderValues(sliders, labels, slidersElement);

      effectsController.load(root);

      Element midiElement = (Element) root.getElementsByTagName("midi").item(0);
      resetCCMap();
      for (int i = 0; i < labels.size(); i++) {
        NodeList elements = midiElement.getElementsByTagName(labels.get(i));
        if (elements.getLength() > 0) {
          Element midi = (Element) elements.item(0);
          int cc = Integer.parseInt(midi.getTextContent());
          Slider slider = sliders.get(i);
          SVGPath midiButton = midiButtonMap.entrySet().stream()
            .filter(entry -> entry.getValue() == slider)
            .findFirst().orElseThrow().getKey();
          mapMidiCC(cc, midiButton);
        }
      }
      root.appendChild(midiElement);

      Element translationElement = (Element) root.getElementsByTagName("translation").item(0);
      Element translationXElement = (Element) translationElement.getElementsByTagName("x").item(0);
      Element translationYElement = (Element) translationElement.getElementsByTagName("y").item(0);
      translationXTextField.setText(translationXElement.getTextContent());
      translationYTextField.setText(translationYElement.getTextContent());

      objController.load(root);

      Element filesElement = (Element) root.getElementsByTagName("files").item(0);
      List<byte[]> files = new ArrayList<>();
      List<String> fileNames = new ArrayList<>();
      NodeList fileElements = filesElement.getElementsByTagName("file");
      for (int i = 0; i < fileElements.getLength(); i++) {
        Element fileElement = (Element) fileElements.item(i);
        String fileData = fileElement.getElementsByTagName("data").item(0).getTextContent();
        files.add(Base64.getDecoder().decode(fileData));
        String fileName = fileElement.getElementsByTagName("name").item(0).getTextContent();
        fileNames.add(fileName);
      }
      updateFiles(files, fileNames);
      openProjectPath = projectFileName;
    } catch (ParserConfigurationException | SAXException | IOException e) {
      e.printStackTrace();
    }
  }
}