module com.teamalpha.practice {
    requires javafx.controls;
    requires javafx.fxml;


    opens com.teamalpha.practice to javafx.fxml;
    exports com.teamalpha.practice;
}