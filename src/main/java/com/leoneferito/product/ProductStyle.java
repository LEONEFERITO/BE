package com.leoneferito.product;

public enum ProductStyle {
    REGULAR(ProductCategory.TROUSERS),
    STRAIGHT(ProductCategory.TROUSERS),
    FLARE(ProductCategory.TROUSERS),
    OXFORD(ProductCategory.SHOES),
    LOAFER(ProductCategory.SHOES);
    private final ProductCategory category;
    ProductStyle(ProductCategory category){
        this.category = category;
    }
    public ProductCategory category(){
        return category;
    }
    public static boolean fits(ProductStyle style, ProductCategory category){
        return style == null || style.category == category;
    }
}
