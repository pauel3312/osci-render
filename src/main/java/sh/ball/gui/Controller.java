package sh.ball.gui;

import sh.ball.MovableRenderer;
import sh.ball.audio.AudioPlayer;
import sh.ball.audio.FrameProducer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.beans.InvalidationListener;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;
import sh.ball.parser.obj.ObjParser;
import sh.ball.parser.ParserFactory;
import sh.ball.parser.txt.TextParser;
import sh.ball.shapes.Shape;
import sh.ball.shapes.Vector2;

public class Controller implements Initializable {

  private static final String DEFAULT_FILE = TextParser.class.getResource("/models/cube.obj").getPath();

  private final FileChooser fileChooser = new FileChooser();
  private final MovableRenderer<List<Shape>, Vector2> renderer = new AudioPlayer();
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  private FrameProducer<List<Shape>> producer = new FrameProducer<>(
    renderer,
    ParserFactory.getParser(DEFAULT_FILE).orElseThrow(FileNotFoundException::new)
  );

  private Stage stage;

  @FXML
  private Button chooseFileButton;
  @FXML
  private Label fileLabel;
  @FXML
  private TextField translationXTextField;
  @FXML
  private TextField translationYTextField;
  @FXML
  private Slider weightSlider;
  @FXML
  private Label weightLabel;
  @FXML
  private Slider rotateSpeedSlider;
  @FXML
  private Label rotateSpeedLabel;
  @FXML
  private Slider translationSpeedSlider;
  @FXML
  private Label translationSpeedLabel;
  @FXML
  private Slider scaleSlider;
  @FXML
  private Label scaleLabel;
  @FXML
  private TitledPane objTitledPane;
  @FXML
  private Slider focalLengthSlider;
  @FXML
  private Label focalLengthLabel;
  @FXML
  private TextField cameraXTextField;
  @FXML
  private TextField cameraYTextField;
  @FXML
  private TextField cameraZTextField;

  public Controller() throws ParserConfigurationException, SAXException, IOException {
  }

  private Map<Slider, SliderUpdater<Double>> initializeSliderMap() {
    return Map.of(
      weightSlider,
      new SliderUpdater<>(weightLabel::setText, renderer::setQuality),
      rotateSpeedSlider,
      new SliderUpdater<>(rotateSpeedLabel::setText, renderer::setRotationSpeed),
      translationSpeedSlider,
      new SliderUpdater<>(translationSpeedLabel::setText, renderer::setTranslationSpeed),
      scaleSlider,
      new SliderUpdater<>(scaleLabel::setText, renderer::setScale)
//      focalLengthSlider,
//      new SliderUpdater<>(focalLengthLabel::setText, producer::setFocalLength)
    );
  }

  @Override
  public void initialize(URL url, ResourceBundle resourceBundle) {
    Map<Slider, SliderUpdater<Double>> sliders = initializeSliderMap();

    for (Slider slider : sliders.keySet()) {
      slider.valueProperty().addListener((source, oldValue, newValue) ->
        sliders.get(slider).update(slider.getValue())
      );
    }

    InvalidationListener translationUpdate = observable ->
      renderer.setTranslation(new Vector2(
        tryParse(translationXTextField.getText()),
        tryParse(translationYTextField.getText())
      ));

    translationXTextField.textProperty().addListener(translationUpdate);
    translationYTextField.textProperty().addListener(translationUpdate);

//    InvalidationListener cameraPosUpdate = observable ->
//      producer.setCameraPos(new Vector3(
//        tryParse(cameraXTextField.getText()),
//        tryParse(cameraYTextField.getText()),
//        tryParse(cameraZTextField.getText())
//      ));
//
//    cameraXTextField.textProperty().addListener(cameraPosUpdate);
//    cameraYTextField.textProperty().addListener(cameraPosUpdate);
//    cameraZTextField.textProperty().addListener(cameraPosUpdate);

    chooseFileButton.setOnAction(e -> {
      File file = null;
      while (file == null) {
        file = fileChooser.showOpenDialog(stage);
      }
      chooseFile(file);
    });

    executor.submit(producer);
    new Thread(renderer).start();
  }

  private double tryParse(String value) {
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private void chooseFile(File file) {
    try {
      producer.stop();
      String path = file.getAbsolutePath();
      producer = new FrameProducer<>(
        renderer,
        ParserFactory.getParser(path)
          .orElseThrow(FileNotFoundException::new)
      );
      executor.submit(producer);

      if (file.exists() && !file.isDirectory()) {
        fileLabel.setText(path);
        objTitledPane.setDisable(!ObjParser.isObjFile(path));
      } else {
        objTitledPane.setDisable(true);
      }
    } catch (IOException | ParserConfigurationException | SAXException ioException) {
      ioException.printStackTrace();
    }
  }

  public void setStage(Stage stage) {
    this.stage = stage;
  }
}