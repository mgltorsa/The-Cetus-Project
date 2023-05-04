package cetus.entities;

import java.util.ArrayList;

import cetus.hir.Traversable;

public class DataRaw {

    private int id;
    private Traversable parent;
    private Traversable value;
    private String typeValue;
    private ArrayList<Traversable> children;
 

    private String lineCode;



    public DataRaw(int id, Traversable parent, Traversable value, String typeValue, ArrayList<Traversable> children,  String lineCode) {
        this.id = id;
        this.parent = parent;
        this.value = value;
        this.typeValue = typeValue;
        this.children = children;
        this.lineCode = lineCode;
    }

    public ArrayList<Traversable> getChildren() {
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
