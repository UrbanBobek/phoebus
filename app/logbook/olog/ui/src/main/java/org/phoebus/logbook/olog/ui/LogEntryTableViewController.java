package org.phoebus.logbook.olog.ui;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.util.Callback;
import javafx.util.Duration;
import org.phoebus.logbook.LogClient;
import org.phoebus.logbook.LogEntry;
import org.phoebus.logbook.olog.ui.LogbookQueryUtil.Keys;
import org.phoebus.olog.es.api.model.OlogLog;
import org.phoebus.ui.javafx.ImageCache;
import org.phoebus.util.time.TimeParser;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * A controller for a log entry table with a collapsible advance search section.
 *
 * @author Kunal Shroff
 */
public class LogEntryTableViewController extends LogbookSearchController {

    static final Image tag = ImageCache.getImage(LogEntryDisplayController.class, "/icons/add_tag.png");
    static final Image logbook = ImageCache.getImage(LogEntryDisplayController.class, "/icons/logbook-16.png");

    @FXML
    private Button resize;
    @FXML
    private Button search;
    @FXML
    private TextField query;

    // elements associated with the various search
    @FXML
    private GridPane ViewSearchPane;

    // elements related to the table view of the log entires
    @FXML
    private TreeView<LogEntry> treeView;

    // detail logview
    @FXML
    public SplitPane logEntryDisplay;
    @FXML
    private LogEntryDisplayController logEntryDisplayController;

    @FXML
    private Node topLevelNode;
    @FXML
    private AdvancedSearchViewController advancedSearchViewController;

    // Model
    List<LogEntry> logEntries;

    // TreeTable root item
    TreeItem<LogEntry> rootItem = new TreeItem<>(new OlogLog());

    // Search parameters
    ObservableMap<Keys, String> searchParameters;

    /**
     * Constructor.
     *
     * @param logClient Log client implementation
     */
    public LogEntryTableViewController(LogClient logClient) {
        setClient(logClient);
    }


    @FXML
    public void initialize() {

        searchParameters = FXCollections.observableHashMap();
        searchParameters.put(Keys.SEARCH, "*");
        searchParameters.put(Keys.STARTTIME, TimeParser.format(java.time.Duration.ofHours(8)));
        searchParameters.put(Keys.ENDTIME, TimeParser.format(java.time.Duration.ZERO));
        advancedSearchViewController.setSearchParameters(searchParameters);

        query.setText(searchParameters.entrySet().stream()
                .sorted(Entry.comparingByKey())
                .map((e) -> e.getKey().getName().trim() + "=" + e.getValue().trim())
                .collect(Collectors.joining("&")));

        searchParameters.addListener((MapChangeListener<Keys, String>) change -> query.setText(searchParameters.entrySet().stream()
                .sorted(Entry.comparingByKey())
                .map((e) -> e.getKey().getName().trim() + "=" + e.getValue().trim())
                .collect(Collectors.joining("&"))));

        // The log entry tree.
        treeView.getSelectionModel().selectedItemProperty().addListener(new ChangeListener<TreeItem<LogEntry>>() {
            @Override
            public void changed(ObservableValue<? extends TreeItem<LogEntry>> observable, TreeItem<LogEntry> oldValue, TreeItem<LogEntry> newValue) {
                // newValue may be null after search and refresh of the tree
                if(newValue != null){
                    logEntryDisplayController.setLogEntry(newValue.getValue());
                }
            }
        });

        treeView.setCellFactory(tv -> new LogEntryTreeCell(tv));
        treeView.getStylesheets().add(this.getClass().getResource("/tree_view.css").toExternalForm());

        // Bind ENTER key press to search
        query.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                search();
            }
        });

        treeView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        treeView.setRoot(rootItem);
        treeView.setShowRoot(false);

    }

    // Keeps track of when the animation is active. Multiple clicks will be ignored
    // until a give resize action is completed
    private AtomicBoolean moving = new AtomicBoolean(false);

    @FXML
    public void resize() {
        if (!moving.compareAndExchangeAcquire(false, true)) {
            if (resize.getText().equals("<")) {
                Duration cycleDuration = Duration.millis(400);
                KeyValue kv = new KeyValue(advancedSearchViewController.getPane().minWidthProperty(), 0);
                KeyValue kv2 = new KeyValue(advancedSearchViewController.getPane().maxWidthProperty(), 0);
                Timeline timeline = new Timeline(new KeyFrame(cycleDuration, kv, kv2));
                timeline.play();
                timeline.setOnFinished(event -> {
                    resize.setText(">");
                    moving.set(false);
                });
            } else {
                Duration cycleDuration = Duration.millis(400);
                double width = ViewSearchPane.getWidth() / 3;
                KeyValue kv = new KeyValue(advancedSearchViewController.getPane().minWidthProperty(), width);
                KeyValue kv2 = new KeyValue(advancedSearchViewController.getPane().prefWidthProperty(), width);
                Timeline timeline = new Timeline(new KeyFrame(cycleDuration, kv, kv2));
                timeline.play();
                timeline.setOnFinished(event -> {
                    resize.setText("<");
                    moving.set(false);
                });
            }
        }
    }

    @FXML
    void updateQuery() {
        Arrays.asList(query.getText().split("&")).forEach(s -> {
            String key = s.split("=")[0];
            for (Keys k : Keys.values()) {
                if (k.getName().equals(key)) {
                    searchParameters.put(k, s.split("=")[1]);
                }
            }
        });
    }

    @FXML
    public void search() {
        // parse the various time representations to Instant
        super.search(LogbookQueryUtil.parseQueryString(query.getText()));
    }

    @FXML
    public void setSelection() {

    }

    @Override
    public void setLogs(List<LogEntry> logs) {
        this.logEntries = logs;
        refresh();
    }

    public void setQuery(String parsedQuery) {
        query.setText(parsedQuery);
        updateQuery();
        search();
    }

    public String getQuery() {
        return query.getText();
    }

    private void refresh() {
        if (logEntries != null) {
            rootItem.getChildren().setAll(logEntries.stream().map(logEntry -> new TreeItem<>(logEntry)).collect(Collectors.toSet()));
            rootItem.getChildren().sort((o1, o2) -> o2.getValue().getCreatedDate().compareTo(o1.getValue().getCreatedDate()));
        }
    }

    private class LogEntryTreeCell extends TreeCell<LogEntry>{

        private Node graphic;
        private LogEntryCellController controller;

        public LogEntryTreeCell(TreeView<LogEntry> treeView) {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("LogEntryCell.fxml"));
                graphic = loader.load();
                controller = loader.getController();
            } catch (IOException exc) {
                throw new RuntimeException(exc);
            }
        }

        @Override
        public void updateItem(LogEntry logEntry, boolean empty) {
            super.updateItem(logEntry, empty);
            if (empty) {
                setGraphic(null);
            } else {
                controller.setLogEntry(logEntry);
                setGraphic(graphic);
            }
        }
    }
}
