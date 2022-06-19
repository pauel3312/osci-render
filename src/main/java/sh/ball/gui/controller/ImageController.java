package sh.ball.gui.controller;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.shape.SVGPath;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import sh.ball.audio.effect.EffectType;
import sh.ball.audio.effect.RotateEffect;
import sh.ball.audio.effect.TranslateEffect;
import sh.ball.audio.engine.AudioDevice;
import sh.ball.audio.midi.MidiNote;
import sh.ball.shapes.Vector2;

import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.function.Consumer;

import static sh.ball.gui.Gui.audioPlayer;
import static sh.ball.math.Math.tryParse;

public class ImageController implements Initializable, SubController {

  private static final double MAX_FREQUENCY = 12000;
  private final DoubleProperty frequency;

  private TranslateEffect translateEffect;
  private double scrollDelta = 0.05;

  @FXML
  private TextField translationXTextField;
  @FXML
  private TextField translationYTextField;
  @FXML
  private CheckBox translateEllipseCheckBox;
  @FXML
  private CheckBox translateCheckBox;
  @FXML
  private Slider frequencySlider;
  @FXML
  private Spinner<Double> frequencySpinner;
  @FXML
  private SVGPath frequencyMidi;
  @FXML
  private CheckBox frequencyMic;
  @FXML
  private Button resetTranslationButton;

  public ImageController() {
    this.frequency = new SimpleDoubleProperty(0);
  }

  public void setTranslateEffect(TranslateEffect effect) {
    this.translateEffect = effect;
  }

  public void setTranslation(Vector2 translation) {
    translationXTextField.setText(MainController.FORMAT.format(translation.getX()));
    translationYTextField.setText(MainController.FORMAT.format(translation.getY()));
  }

  public void setTranslationIncrement(double increment) {
    this.scrollDelta = increment;
  }

  // changes the sinusoidal translation of the image rendered
  private void updateTranslation() {
    translateEffect.setTranslation(new Vector2(
      tryParse(translationXTextField.getText()),
      tryParse(translationYTextField.getText())
    ));
  }

  private void changeTranslation(boolean increase, TextField field) {
    double old = tryParse(field.getText());
    double delta = increase ? scrollDelta : -scrollDelta;
    field.setText(MainController.FORMAT.format(old + delta));
  }

  public boolean mouseTranslate() {
    return translateCheckBox.isSelected();
  }

  public void disableMouseTranslate() {
    translateCheckBox.setSelected(false);
  }

  @Override
  public void initialize(URL url, ResourceBundle resourceBundle) {
    resetTranslationButton.setOnAction(e -> {
      translationXTextField.setText("0.00");
      translationYTextField.setText("0.00");
    });

    translationXTextField.textProperty().addListener(e -> updateTranslation());
    translationXTextField.setOnScroll((e) -> changeTranslation(e.getDeltaY() > 0, translationXTextField));
    translationYTextField.textProperty().addListener(e -> updateTranslation());
    translationYTextField.setOnScroll((e) -> changeTranslation(e.getDeltaY() > 0, translationYTextField));

    translateEllipseCheckBox.selectedProperty().addListener((e, old, ellipse) -> translateEffect.setEllipse(ellipse));

    frequencySpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0, MAX_FREQUENCY, MidiNote.MIDDLE_C, 1));
    frequencySpinner.valueProperty().addListener((o, old, f) -> frequency.set(f));

    frequency.addListener((o, old, f) -> {
      frequencySlider.setValue(Math.log(f.doubleValue()) / Math.log(MAX_FREQUENCY));
      frequencySpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0, MAX_FREQUENCY, f.doubleValue(), 1));
    });
    audioPlayer.setFrequency(frequency);
    // default value is middle C
    frequency.set(MidiNote.MIDDLE_C);
    frequencySlider.setOnKeyPressed(e -> {
      if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.RIGHT) {
        slidersUpdated();
      }
    });
    frequencySlider.setOnMouseDragged(e -> slidersUpdated());
  }

  @Override
  public Map<SVGPath, Slider> getMidiButtonMap() {
    return Map.of(
      frequencyMidi, frequencySlider
    );
  }

  @Override
  public List<CheckBox> micCheckBoxes() {
    return List.of(frequencyMic);
  }

  @Override
  public List<Slider> sliders() {
    return List.of(frequencySlider);
  }

  @Override
  public List<String> labels() {
    return List.of("frequency");
  }

  @Override
  public Element save(Document document) {
    Element element = document.createElement("translation");
    Element x = document.createElement("x");
    x.appendChild(document.createTextNode(translationXTextField.getText()));
    Element y = document.createElement("y");
    y.appendChild(document.createTextNode(translationYTextField.getText()));
    Element ellipse = document.createElement("ellipse");
    ellipse.appendChild(document.createTextNode(Boolean.toString(translateEllipseCheckBox.isSelected())));
    element.appendChild(x);
    element.appendChild(y);
    element.appendChild(ellipse);
    return element;
  }

  @Override
  public void load(Element root) {
    Element element = (Element) root.getElementsByTagName("translation").item(0);
    Element x = (Element) element.getElementsByTagName("x").item(0);
    Element y = (Element) element.getElementsByTagName("y").item(0);
    translationXTextField.setText(x.getTextContent());
    translationYTextField.setText(y.getTextContent());

    slidersUpdated();

    // For backwards compatibility we assume a default value
    Element ellipse = (Element) element.getElementsByTagName("ellipse").item(0);
    translateEllipseCheckBox.setSelected(ellipse != null && Boolean.parseBoolean(ellipse.getTextContent()));
  }

  @Override
  public void slidersUpdated() {
    frequency.set(Math.pow(MAX_FREQUENCY, frequencySlider.getValue()));
  }
}
