package cetus.entities;

import java.util.ArrayList;
import java.util.List;

import cetus.hir.Traversable;

public class DataRaw {

    private int id;
    private Traversable parent;
    private Traversable value;
    private String typeValue;
    private List<Traversable> children;

    private String filename;
    private String lineCode;
    private String columnCode;

    public String getColumnCode() {
        return columnCode;
    }



    public void setColumnCode(String columnCode) {
        this.columnCode = columnCode;
    }



    public void setParent(Traversable parent) {
        this.parent = parent;
    }



    public void setFilename(String filename) {
        this.filename = filename;
    }



    public Traversable getParent() {
        return parent;
    }



    public String getFilename() {
        return filename;
    }



    public DataRaw(int id, Traversable parent, Traversable value, String typeValue, List<Traversable> children) {
        this.id = id;
        this.parent = parent;
        this.value = value;
        this.typeValue = typeValue;
        this.children = children;
    }

    

    public List<Traversable> getChildren() {
        return children;
    }

    public void setChildren(ArrayList<Traversable> children) {
        this.children = children;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public Traversable getparent() {
        return parent;
    }

    public void setgetparent(Traversable parent) {
        this.parent = parent;
    }

    public Traversable getValue() {
        return value;
    }

    public void setValue(Traversable value) {
        this.value = value;
    }

    public String getTypeValue() {
        return typeValue;
    }

    public void setTypeValue(String typeValue) {
        this.typeValue = typeValue;
    }

    public String getLineCode() {
        return lineCode;
    }

    public void setLineCode(String lineCode) {
        this.lineCode = lineCode;
    }

}
